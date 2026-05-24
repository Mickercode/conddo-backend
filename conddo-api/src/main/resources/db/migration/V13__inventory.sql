-- =====================================================================
-- V13 — Inventory (§11.6): products, categories, and stock adjustments.
-- All tenant business data → RLS per the §3 checklist (tenant_id column, RLS
-- enabled, NULLIF-hardened tenant_isolation policy, DML grants to the app role).
-- =====================================================================

CREATE TABLE product_categories (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  UUID        NOT NULL REFERENCES tenants (id),
    name       TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, name)
);

CREATE TABLE products (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID        NOT NULL REFERENCES tenants (id),
    name              TEXT        NOT NULL,
    sku               TEXT,
    category_id       UUID        REFERENCES product_categories (id) ON DELETE SET NULL,
    price             NUMERIC(14, 2) NOT NULL DEFAULT 0,
    stock             INTEGER     NOT NULL DEFAULT 0,
    reorder_threshold INTEGER     NOT NULL DEFAULT 0,
    active            BOOLEAN     NOT NULL DEFAULT true,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_products_tenant ON products (tenant_id);
CREATE INDEX idx_products_category ON products (category_id);

CREATE TABLE stock_adjustments (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  UUID        NOT NULL REFERENCES tenants (id),
    product_id UUID        NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    delta      INTEGER     NOT NULL,
    reason     TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_stock_adjustments_product ON stock_adjustments (product_id, created_at);

GRANT SELECT, INSERT, UPDATE, DELETE ON product_categories TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON products           TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON stock_adjustments  TO ${app_role};

ALTER TABLE product_categories ENABLE ROW LEVEL SECURITY;
ALTER TABLE products           ENABLE ROW LEVEL SECURITY;
ALTER TABLE stock_adjustments  ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON product_categories
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY tenant_isolation ON products
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY tenant_isolation ON stock_adjustments
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
