-- =====================================================================
-- V18 — Website module (§11.2). The tenant side is read + request-changes:
-- the actual site is built in Conddo Studio (§8). We track publish state on the
-- tenants row (like the other tenant-level config in V12/V14) and record owner
-- edit requests in a tenant-scoped table. Linking a request to a Studio job is a
-- soft reference (studio_job_id) — the cross-service call is deferred.
-- =====================================================================

ALTER TABLE tenants
    ADD COLUMN website_status       TEXT        NOT NULL DEFAULT 'NOT_STARTED',  -- NOT_STARTED | IN_PROGRESS | LIVE
    ADD COLUMN website_published_at TIMESTAMPTZ;

CREATE TABLE website_change_requests (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID        NOT NULL REFERENCES tenants (id),
    area          TEXT,                                   -- which section/area to edit (e.g. 'hero', 'general')
    details       TEXT        NOT NULL,                   -- the requested change, free text
    status        TEXT        NOT NULL DEFAULT 'PENDING', -- PENDING | IN_PROGRESS | DONE | REJECTED
    studio_job_id UUID,                                   -- soft link to the Studio job (no cross-service FK)
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_website_change_requests_tenant ON website_change_requests (tenant_id, created_at);

GRANT SELECT, INSERT, UPDATE, DELETE ON website_change_requests TO ${app_role};

ALTER TABLE website_change_requests ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON website_change_requests
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
