-- =====================================================================
-- V48 — Staff sub-role expansion (HANDOFF_2026-06-12).
--
-- The two platform roles stay (TENANT_ADMIN / STAFF / SUPER_ADMIN). A
-- new staff_role discriminator on `users` lets STAFF users carry one
-- of five sub-roles (Manager / Pharmacist / Cashier / Stock Manager /
-- Bookkeeper) so the FE can route them to /work/<role> and the BE
-- can gate per-module access without exploding the role enum.
--
-- FE handoff used "tenant_users"; the actual table is `users`.
-- =====================================================================

ALTER TABLE users
    ADD COLUMN staff_role VARCHAR(20);

-- Backfill: every existing STAFF user becomes MANAGER (the broadest
-- sub-role) so existing access is preserved exactly. Owners and
-- SUPER_ADMIN stay NULL.
UPDATE users
   SET staff_role = 'MANAGER'
 WHERE role = 'STAFF'
   AND staff_role IS NULL;

ALTER TABLE users
    ADD CONSTRAINT users_staff_role_check
    CHECK (staff_role IS NULL OR staff_role IN
        ('MANAGER', 'PHARMACIST', 'CASHIER', 'STOCK_MANAGER', 'BOOKKEEPER'));

-- Pair invariant — TENANT_ADMIN must NOT carry a sub-role; STAFF
-- must. Other roles (SUPER_ADMIN, future CUSTOMER, etc.) are
-- unconstrained.
ALTER TABLE users
    ADD CONSTRAINT users_staff_role_pair
    CHECK (
        (role = 'TENANT_ADMIN' AND staff_role IS NULL) OR
        (role = 'STAFF' AND staff_role IS NOT NULL) OR
        (role NOT IN ('TENANT_ADMIN', 'STAFF'))
    );

CREATE INDEX idx_users_staff_role ON users (tenant_id, staff_role)
    WHERE staff_role IS NOT NULL;
