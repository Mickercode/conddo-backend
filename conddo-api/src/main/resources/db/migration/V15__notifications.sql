-- =====================================================================
-- V15 — In-app notifications (§11.12): the topbar bell feed. Tenant business
-- data → RLS per the §3 checklist. user_id is nullable: null = tenant-wide
-- (every user sees it), otherwise targeted at one user. read is a simple shared
-- flag for now (per-user read state is a future refinement).
-- =====================================================================

CREATE TABLE notifications (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  UUID        NOT NULL REFERENCES tenants (id),
    user_id    UUID,
    type       TEXT        NOT NULL,          -- BOOKING | ORDER | PAYMENT | SYSTEM ...
    title      TEXT        NOT NULL,
    body       TEXT,
    read       BOOLEAN     NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_notifications_tenant_created ON notifications (tenant_id, created_at);

GRANT SELECT, INSERT, UPDATE, DELETE ON notifications TO ${app_role};

ALTER TABLE notifications ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON notifications
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
