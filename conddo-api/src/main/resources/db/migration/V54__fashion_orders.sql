-- =====================================================================
-- V54 — Fashion Orders (Fashion vertical): shoe orders with size/color selection.
-- All tenant business data → RLS per the §3 checklist (tenant_id column, RLS
-- enabled, NULLIF-hardened tenant_isolation policy, DML grants to the app role).
-- =====================================================================

CREATE TABLE fashion_orders (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID        NOT NULL REFERENCES tenants (id),
    reference         TEXT        NOT NULL,
    customer_id       UUID,
    customer_name     TEXT        NOT NULL,
    stage             TEXT        NOT NULL, -- Received, Processing, Production, Quality Check, Ready, Shipped, Delivered
    total_amount      NUMERIC(14, 2) NOT NULL DEFAULT 0,
    order_date        TIMESTAMPTZ NOT NULL,
    expected_delivery TIMESTAMPTZ,
    notes             TEXT,
    flag              TEXT, -- URGENT, OVERDUE
    items             JSONB,      -- Fashion order items with size/color selection
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_fashion_orders_tenant ON fashion_orders (tenant_id);
CREATE INDEX idx_fashion_orders_customer ON fashion_orders (customer_id);
CREATE INDEX idx_fashion_orders_stage ON fashion_orders (stage);
CREATE INDEX idx_fashion_orders_reference ON fashion_orders (reference);

GRANT SELECT, INSERT, UPDATE, DELETE ON fashion_orders TO ${app_role};

ALTER TABLE fashion_orders ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON fashion_orders
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
