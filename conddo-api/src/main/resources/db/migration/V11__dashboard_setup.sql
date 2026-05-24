-- =====================================================================
-- V11 — Dashboard setup checklist (§11.1).
-- Most checklist steps are derived from tenant state (has a customer, an order,
-- a payment, a chosen vertical). This column records steps the owner has
-- explicitly dismissed so they read as done even when not satisfied.
-- tenants is not tenant-scoped (no RLS); it already grants UPDATE to the app
-- role, and the app only ever updates its own tenant row (by id).
-- =====================================================================

ALTER TABLE tenants
    ADD COLUMN setup_dismissed JSONB NOT NULL DEFAULT '[]'::jsonb;
