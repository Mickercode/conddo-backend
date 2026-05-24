-- =====================================================================
-- V4 — Auth privileges & RLS rationale (PRD §6.1 / §12.2)
--
-- Run by Flyway as the OWNER role. Grants runtime DML on the new
-- password_reset_tokens table to the application role (${app_role}).
-- refresh_tokens was already granted in V2.
--
-- DELIBERATE — no tenant_isolation RLS policy is added to refresh_tokens or
-- password_reset_tokens. These are credential-bootstrapping tables: they are
-- read on UNAUTHENTICATED requests (token refresh, password reset) where no
-- tenant context exists yet, so a policy keyed on app.tenant_id would fail
-- closed and break the flow. They are protected instead by a high-entropy,
-- unguessable `selector` (unique, never enumerated), and every row carries its
-- own tenant_id — so tenant scope is re-established from the row once read.
-- Finishing RLS coverage here (ACTION_LIST item 4) depends on the subdomain
-- resolver (item 2) supplying tenant context at the edge first.
-- =====================================================================

GRANT SELECT, INSERT, UPDATE, DELETE ON password_reset_tokens TO ${app_role};
