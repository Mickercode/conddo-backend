-- =====================================================================
-- V8 — Audit log: append-only + Row Level Security (PRD §6.5, §12.5).
--
-- The audit_log table (V1) is now wired up:
--   * append-only — the app may INSERT and SELECT but not UPDATE/DELETE;
--   * RLS — reads are scoped to the current tenant, while INSERTs are open so
--     trusted server code can record any event, including platform-level rows
--     with a NULL tenant_id (super-admin / system actions). Writes happen in a
--     REQUIRES_NEW transaction with no app.tenant_id bound, so the permissive
--     WITH CHECK is also what lets those inserts through.
--   * ip_address moves from INET to TEXT for straightforward String mapping
--     (the table is empty, so the type change is free).
-- =====================================================================

ALTER TABLE audit_log ALTER COLUMN ip_address TYPE TEXT USING ip_address::text;

-- Append-only for the application role.
REVOKE UPDATE, DELETE ON audit_log FROM ${app_role};

ALTER TABLE audit_log ENABLE ROW LEVEL SECURITY;

-- Reads: only the current tenant's rows (NULLIF guards the empty-GUC case, as in V5).
CREATE POLICY audit_read ON audit_log FOR SELECT
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- Appends: open. Audit rows are written only by trusted server code, are
-- append-only, and may legitimately carry any (or no) tenant_id.
CREATE POLICY audit_append ON audit_log FOR INSERT
    WITH CHECK (true);
