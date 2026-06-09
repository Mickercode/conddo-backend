-- =====================================================================
-- V36 — Pharmacy Module Spec v2 §12E: Refill Offers.
--
-- A pharmacist defines a refill offer per product. After dispensing
-- an order, they "issue" the offer to a specific customer — that
-- creates a claim row with a short, uppercase, collision-resistant
-- code (REFILL-XXXX) and an expires_at = issued_at + valid_days.
--
-- When the customer presents the code at checkout (Spec v2 §12E),
-- the public validator confirms it's live and unused, the checkout
-- applies the offer's discount to the matching line, and the claim
-- row is marked used with the resulting order_id.
-- =====================================================================

-- One offer per (tenant, product) typically; the spec doesn't forbid
-- multiples, so no unique constraint. is_active flips to false when
-- the tenant pauses or retires the offer.
CREATE TABLE pharmacy_refill_offers (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID         NOT NULL REFERENCES tenants (id),
    -- No FK on product_id — same audit-survivability reasoning as
    -- pharmacy_discounts.
    product_id      UUID         NOT NULL,
    discount_type   VARCHAR(20)  NOT NULL
        CHECK (discount_type IN ('PERCENTAGE', 'FIXED')),
    discount_value  NUMERIC(12, 2) NOT NULL CHECK (discount_value > 0),
    valid_days      INTEGER      NOT NULL CHECK (valid_days > 0),
    max_uses        INTEGER      NOT NULL DEFAULT 1 CHECK (max_uses > 0),
    message         TEXT,
    is_active       BOOLEAN      NOT NULL DEFAULT true,
    created_by      UUID         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_refill_offers_tenant_product
    ON pharmacy_refill_offers (tenant_id, product_id, is_active);

-- One row per (offer, customer, time-of-issue). Distinct
-- `offer_code` is the global identifier — short, uppercase, picked
-- by the service to avoid collisions; not bytes-of-entropy strong,
-- but tenant-isolated so the search space is per-tenant anyway.
CREATE TABLE pharmacy_refill_offer_claims (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID         NOT NULL REFERENCES tenants (id),
    offer_id        UUID         NOT NULL REFERENCES pharmacy_refill_offers (id) ON DELETE CASCADE,
    customer_id     UUID         NOT NULL,
    offer_code      VARCHAR(40)  NOT NULL UNIQUE,
    issued_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ  NOT NULL,
    used_at         TIMESTAMPTZ,
    order_id        UUID
);

CREATE INDEX idx_refill_claims_tenant_customer
    ON pharmacy_refill_offer_claims (tenant_id, customer_id, expires_at DESC);
CREATE INDEX idx_refill_claims_tenant_code
    ON pharmacy_refill_offer_claims (tenant_id, offer_code);

GRANT SELECT, INSERT, UPDATE, DELETE ON pharmacy_refill_offers       TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON pharmacy_refill_offer_claims TO ${app_role};

ALTER TABLE pharmacy_refill_offers       ENABLE ROW LEVEL SECURITY;
ALTER TABLE pharmacy_refill_offer_claims ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON pharmacy_refill_offers
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY tenant_isolation ON pharmacy_refill_offer_claims
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
