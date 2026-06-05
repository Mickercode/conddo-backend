-- =====================================================================
-- Pharmacy deep-dive Phase 1 (PHARMACY_DEEP_DIVE_SPEC.md).
--
-- Two changes:
--   1. New tenant-scoped `prescriptions` table (with the standard RLS
--      policy and ${app_role} grants).
--   2. Two new columns on `products` for expiry-aware inventory.
--
-- next_refill_due is computed in the service layer on every write
-- (Postgres generated columns can't reference CASE on nullable
-- timestamps cleanly across major versions, so the spec calls this out
-- as service-side recompute). The column is stored so list filters
-- (`due_soon`, `overdue`) read it directly without a CASE per row.
-- =====================================================================

CREATE TABLE prescriptions (
    id                    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID         NOT NULL REFERENCES tenants (id),
    customer_id           UUID         NOT NULL REFERENCES customers (id),

    medication            VARCHAR(160) NOT NULL,
    dosage                VARCHAR(120),
    quantity              INTEGER,
    refill_interval_days  INTEGER,
    notes                 TEXT,

    issued_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_filled_at        TIMESTAMPTZ,
    next_refill_due       DATE,
    -- 12-hour reminder de-dupe — soft guard for `/remind` and the future
    -- scheduled-reminder cron. NULL when no reminder has ever been sent.
    last_reminded_at      TIMESTAMPTZ,

    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_prescriptions_due
    ON prescriptions (tenant_id, next_refill_due);
CREATE INDEX idx_prescriptions_customer
    ON prescriptions (tenant_id, customer_id);

-- Runtime DML for the app role (RLS still enforced — see V2 pattern).
GRANT SELECT, INSERT, UPDATE, DELETE ON prescriptions TO ${app_role};

ALTER TABLE prescriptions ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON prescriptions
    USING      (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- Expiry-aware inventory. expiry_date NULL = "not tracked" (legacy /
-- non-pharmacy rows). batch_number is informational at V1; lot
-- tracking proper is Phase 2.
ALTER TABLE products
    ADD COLUMN expiry_date   DATE,
    ADD COLUMN batch_number  VARCHAR(80);

CREATE INDEX idx_products_expiry
    ON products (tenant_id, expiry_date)
    WHERE expiry_date IS NOT NULL;
