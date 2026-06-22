-- =====================================================================
-- V53 — Fashion Products (Fashion vertical): shoe products with size/color/material variants.
-- All tenant business data → RLS per the §3 checklist (tenant_id column, RLS
-- enabled, NULLIF-hardened tenant_isolation policy, DML grants to the app role).
-- =====================================================================

CREATE TABLE fashion_products (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID        NOT NULL REFERENCES tenants (id),
    name              TEXT        NOT NULL,
    sku               TEXT,
    category          TEXT        NOT NULL, -- Sneakers, Formal, Casual, Boots, Sandals, Loafers, Athletic
    material          TEXT        NOT NULL, -- Leather, Suede, Canvas, Synthetic, Textile, Rubber
    base_price        NUMERIC(14, 2) NOT NULL DEFAULT 0,
    total_stock       INTEGER     NOT NULL DEFAULT 0,
    active            BOOLEAN     NOT NULL DEFAULT true,
    variants          JSONB,      -- Size/color variants stored as JSON array
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_fashion_products_tenant ON fashion_products (tenant_id);
CREATE INDEX idx_fashion_products_category ON fashion_products (category);
CREATE INDEX idx_fashion_products_material ON fashion_products (material);
CREATE INDEX idx_fashion_products_sku ON fashion_products (sku);

GRANT SELECT, INSERT, UPDATE, DELETE ON fashion_products TO ${app_role};

ALTER TABLE fashion_products ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON fashion_products
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
