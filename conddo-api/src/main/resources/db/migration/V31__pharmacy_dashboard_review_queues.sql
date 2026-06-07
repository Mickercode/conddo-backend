-- =====================================================================
-- V31 — Pharmacy dashboard review queues (HANDOFF_2026-06-07 bugs 1+2).
--
-- The FE pharmacist dashboard expects two endpoints that returned 500
-- because the underlying tables didn't exist yet:
--   GET    /api/v1/pharmacy/customer-prescriptions
--   PATCH  /api/v1/pharmacy/customer-prescriptions/{id}/review
--   GET    /api/v1/pharmacy/consultations
--   PATCH  /api/v1/pharmacy/consultations/{id}/status
--
-- Distinct from the existing `prescriptions` table (V23) which is the
-- internal dispensing log the merchant writes themselves. These two
-- tables hold what the merchant's customers submit from the public
-- website — review queues for the pharmacist.
--
-- PHARMACY_PUBLIC_API_SPEC §7 (submit) + §8 (consult) cover the inbound
-- public endpoints. This migration models the storage only; the public
-- submit endpoints land in the larger Seb&Bayor integration slice (FE
-- handoff item #7).
-- =====================================================================

CREATE TABLE customer_prescriptions (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID         NOT NULL REFERENCES tenants (id),
    customer_id         UUID         REFERENCES customers (id),
    customer_name       TEXT         NOT NULL,
    customer_phone      TEXT,
    file_url            TEXT         NOT NULL,
    patient_name        TEXT         NOT NULL,
    prescriber_name     TEXT         NOT NULL,
    notes               TEXT,
    status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
                                     -- PENDING | APPROVED | REJECTED
    review_note         TEXT,
    reviewed_at         TIMESTAMPTZ,
    reviewed_by         UUID         REFERENCES users (id),
    reviewed_by_name    TEXT,
    order_id            UUID         REFERENCES orders (id),
    submitted_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_customer_prescriptions_tenant_status ON customer_prescriptions
    (tenant_id, status, submitted_at DESC);
CREATE INDEX idx_customer_prescriptions_customer       ON customer_prescriptions (customer_id);

CREATE TABLE consultations (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID         NOT NULL REFERENCES tenants (id),
    customer_id         UUID         REFERENCES customers (id),
    customer_name       TEXT         NOT NULL,
    whatsapp_number     TEXT         NOT NULL,
    topic               TEXT         NOT NULL,
    preferred_time      TEXT,
    status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
                                     -- PENDING | CONFIRMED | COMPLETED | CANCELLED
    pharmacist_note     TEXT,
    completed_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_consultations_tenant_status ON consultations
    (tenant_id, status, created_at DESC);
CREATE INDEX idx_consultations_customer      ON consultations (customer_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON customer_prescriptions TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON consultations          TO ${app_role};

ALTER TABLE customer_prescriptions ENABLE ROW LEVEL SECURITY;
ALTER TABLE consultations          ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON customer_prescriptions
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY tenant_isolation ON consultations
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
