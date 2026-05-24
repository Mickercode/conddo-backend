-- =====================================================================
-- V10 — Orders module (§11.4): orders + items + payments + activity +
-- per-tenant pipeline stages. Every table is tenant-scoped, so each follows
-- the §3 checklist: a tenant_id column, RLS enabled, the NULLIF-hardened
-- tenant_isolation policy (V5), and DML grants to the application role.
-- =====================================================================

-- Human-readable order references ("ORD-2894"). A single global sequence keeps
-- generation lock-free; the value is for display only (the UUID id is canonical).
CREATE SEQUENCE order_reference_seq START 2894;

CREATE TABLE orders (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID        NOT NULL REFERENCES tenants (id),
    reference     TEXT        NOT NULL UNIQUE DEFAULT ('ORD-' || nextval('order_reference_seq')),
    -- customer link is optional and survives customer deletion (name is snapshotted).
    customer_id   UUID        REFERENCES customers (id) ON DELETE SET NULL,
    customer_name TEXT,
    service       TEXT,
    stage         TEXT        NOT NULL,
    amount        NUMERIC(14, 2) NOT NULL DEFAULT 0,
    due_date      DATE,
    flag          TEXT,                       -- manual 'URGENT'; 'OVERDUE' is derived at read time
    notes         TEXT,
    measurements  JSONB,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_orders_tenant_stage ON orders (tenant_id, stage);
CREATE INDEX idx_orders_customer ON orders (customer_id);

CREATE TABLE order_items (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID        NOT NULL REFERENCES tenants (id),
    order_id    UUID        NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    description TEXT        NOT NULL,
    quantity    INTEGER     NOT NULL DEFAULT 1,
    unit_price  NUMERIC(14, 2) NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_order_items_order ON order_items (order_id);

CREATE TABLE order_payments (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  UUID        NOT NULL REFERENCES tenants (id),
    order_id   UUID        NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    amount     NUMERIC(14, 2) NOT NULL,
    method     TEXT,
    note       TEXT,
    paid_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_order_payments_order ON order_payments (order_id);

CREATE TABLE order_activity (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  UUID        NOT NULL REFERENCES tenants (id),
    order_id   UUID        NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    type       TEXT        NOT NULL,          -- STAGE_CHANGE | PAYMENT | MESSAGE | NOTE
    title      TEXT        NOT NULL,
    detail     TEXT,
    actor      TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_order_activity_order ON order_activity (order_id, created_at);

-- Per-tenant pipeline stages. Empty for a tenant => the dashboard falls back to
-- the vertical's default stages (VerticalConfig); rows here are tenant overrides.
CREATE TABLE order_stages (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  UUID        NOT NULL REFERENCES tenants (id),
    name       TEXT        NOT NULL,
    position   INTEGER     NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, name)
);

CREATE INDEX idx_order_stages_tenant ON order_stages (tenant_id, position);

-- Runtime DML privileges for the application role + sequence usage for the ref default.
GRANT SELECT, INSERT, UPDATE, DELETE ON orders         TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON order_items    TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON order_payments TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON order_activity TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON order_stages   TO ${app_role};
GRANT USAGE, SELECT ON SEQUENCE order_reference_seq    TO ${app_role};

-- Enable RLS + the NULLIF-hardened tenant-isolation policy on every table.
ALTER TABLE orders         ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_items    ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_payments ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_activity ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_stages   ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON orders
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY tenant_isolation ON order_items
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY tenant_isolation ON order_payments
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY tenant_isolation ON order_activity
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY tenant_isolation ON order_stages
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
