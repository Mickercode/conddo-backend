-- =====================================================================
-- V19 — Media library (§11.12). Tenant-uploaded files (logos, product images,
-- marketing media, documents) live in S3-compatible object storage (MinIO / R2 /
-- S3); this table is the tenant-scoped index of them. Bytes are NOT in Postgres —
-- only the object key + metadata. Serving is via short-lived presigned URLs, so
-- the bucket can stay private. Tenant-scoped → the §3 RLS checklist.
-- =====================================================================

CREATE TABLE media_assets (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID        NOT NULL REFERENCES tenants (id),
    storage_key   TEXT        NOT NULL,            -- object key in the bucket
    content_type  TEXT,
    size_bytes    BIGINT      NOT NULL DEFAULT 0,
    original_name TEXT,
    kind          TEXT,                            -- logo | product | post | document | other
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_media_assets_tenant ON media_assets (tenant_id, kind, created_at);

GRANT SELECT, INSERT, UPDATE, DELETE ON media_assets TO ${app_role};

ALTER TABLE media_assets ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON media_assets
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
