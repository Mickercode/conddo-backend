-- =====================================================================
-- V9 — CRM fields on customers (§11.3 dashboard module).
-- Adds tags + measurement profile (both JSONB) and a last-active timestamp.
-- customers is already RLS-scoped (V2) and granted to the app role, so no
-- new policy/grant is needed — these are columns on an existing tenant table.
-- =====================================================================

ALTER TABLE customers
    ADD COLUMN tags         JSONB       NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN measurements JSONB,
    ADD COLUMN last_active  TIMESTAMPTZ;
