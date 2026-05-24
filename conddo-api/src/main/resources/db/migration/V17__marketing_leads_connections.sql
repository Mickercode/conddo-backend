-- =====================================================================
-- V17 — Marketing leads + connected social accounts (§11.8). Tenant business
-- data → RLS per the §3 checklist. Real social OAuth is deferred — a connection
-- row just records that a handle was linked.
-- =====================================================================

CREATE TABLE marketing_leads (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  UUID        NOT NULL REFERENCES tenants (id),
    name       TEXT        NOT NULL,
    email      TEXT,
    phone      TEXT,
    source     TEXT,
    stage      TEXT        NOT NULL DEFAULT 'new',   -- new | contacted | interested | converted
    value      NUMERIC(14, 2) NOT NULL DEFAULT 0,
    notes      TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_marketing_leads_tenant_stage ON marketing_leads (tenant_id, stage);

CREATE TABLE marketing_connections (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID        NOT NULL REFERENCES tenants (id),
    platform     TEXT        NOT NULL,                 -- instagram | facebook | x | linkedin
    handle       TEXT,
    status       TEXT        NOT NULL DEFAULT 'connected',
    connected_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, platform)
);

GRANT SELECT, INSERT, UPDATE, DELETE ON marketing_leads       TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON marketing_connections TO ${app_role};

ALTER TABLE marketing_leads       ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketing_connections ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON marketing_leads
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY tenant_isolation ON marketing_connections
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
