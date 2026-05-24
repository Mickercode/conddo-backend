-- =====================================================================
-- V5 — Harden RLS against an empty app.tenant_id GUC (PRD §6.1).
--
-- Subtlety: once a placeholder GUC (app.tenant_id) has been set in a session,
-- PostgreSQL keeps the parameter known; after a transaction-local value expires
-- (commit/rollback), current_setting('app.tenant_id', true) returns '' (empty
-- string), NOT NULL. The V2 policies cast that straight to uuid, so an unscoped
-- query then ERRORS ('' ::uuid) instead of failing closed.
--
-- Wrapping in NULLIF(..., '') turns the empty string back into NULL, which
-- matches no rows — so isolation fails closed gracefully (zero rows), never
-- open and never with an error. New tenant-scoped tables should use this form.
-- =====================================================================

ALTER POLICY tenant_isolation ON customers
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER POLICY tenant_isolation ON users
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
