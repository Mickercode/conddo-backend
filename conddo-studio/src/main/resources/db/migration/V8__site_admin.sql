-- =====================================================================
-- Studio V8 — Site Registration admin (SITE_REGISTRATION_ADMIN_SPEC §6).
--
-- Ops needs an audit trail every time they register, rotate, QA-approve,
-- activate/deactivate, or edit metadata on a tenant_sites row. The
-- tenant_sites table itself lives in `public` (owned by conddo-api,
-- shipped in V25 of the platform schema); this audit log lives in
-- `studio` so platform-schema migrations don't have to know about it.
--
-- FK relationships are soft (UUIDs only) — Studio already follows that
-- pattern for tenant_id on jobs. site_id values are inserted at the
-- moment the matching tenant_sites row is created or referenced, so
-- referential integrity is enforced by service code, not the DB.
-- =====================================================================

CREATE TABLE studio.site_audit_log (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    site_id         UUID         NOT NULL,
    action          VARCHAR(40)  NOT NULL,
    -- REGISTERED | KEY_ROTATED | QA_APPROVED | QA_REVOKED
    -- ACTIVATED | DEACTIVATED | METADATA_UPDATED
    by_staff_id     UUID         NOT NULL,
    detail          TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_site_audit_site_created
    ON studio.site_audit_log (site_id, created_at DESC);

CREATE INDEX idx_site_audit_staff
    ON studio.site_audit_log (by_staff_id, created_at DESC);
