-- =====================================================================
-- V38 — Widen pharmacy_discounts RLS to allow the cross_tenant
-- carve-out used by the hourly expiry sweeper (Slice 5 followup).
--
-- The original V35 policy locks every read+update to the bound
-- tenant. The new sweeper runs as a cron with no JWT context — it
-- sets `app.cross_tenant = true` (V26 pattern) before walking every
-- APPROVED discount past its ends_at and flipping the status to
-- EXPIRED.
-- =====================================================================

DROP POLICY tenant_isolation ON pharmacy_discounts;

CREATE POLICY tenant_isolation ON pharmacy_discounts
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true')
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true');
