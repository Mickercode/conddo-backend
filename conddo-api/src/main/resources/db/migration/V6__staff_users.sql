-- =====================================================================
-- V6 — Internal staff axis (PRD v1.3 §22)
--
-- Handel Cores staff are NOT tenant-scoped — they work across all tenants (or,
-- like SUPER_ADMIN, on the platform itself). Keeping them OUT of `users` is
-- deliberate: it keeps the tenant-facing `users` table + RLS cleanly
-- tenant-scoped and isolates the internal axis. These tables therefore have NO
-- tenant_id and NO RLS — they are global platform tables, gated at the app
-- layer (the /auth/staff/* endpoints), not by Postgres tenant isolation.
--
-- SUPER_ADMIN lives here now. The Conddo Studio roles (Website Developer, QA
-- Reviewer, Production Team Lead — Phase 3) will be added as further
-- internal_role values; no schema change needed for them.
-- =====================================================================

CREATE TABLE staff_users (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email                 TEXT        NOT NULL UNIQUE,   -- global; staff have no tenant
    password_hash         TEXT        NOT NULL,
    full_name             TEXT,
    internal_role         TEXT        NOT NULL,
    is_active             BOOLEAN     NOT NULL DEFAULT TRUE,
    failed_login_attempts INT         NOT NULL DEFAULT 0,
    locked_until          TIMESTAMPTZ,
    last_login_at         TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

GRANT SELECT, INSERT, UPDATE, DELETE ON staff_users TO ${app_role};

-- The sentinel platform tenant (seeded in V3) was a home for SUPER_ADMIN while
-- it lived in `users`. SUPER_ADMIN now lives in staff_users, so the sentinel is
-- obsolete. Safe to remove — nothing references it (it never had customers).
DELETE FROM tenants WHERE id = '00000000-0000-0000-0000-000000000001';
