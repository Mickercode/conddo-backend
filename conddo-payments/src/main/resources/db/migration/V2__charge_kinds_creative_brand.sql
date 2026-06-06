-- =====================================================================
-- Conddo Payments V2 — extend payments with creative-service + brand-
-- package charge kinds (SOCIAL_AND_CREATIVE_SERVICES_SPEC §5, §6).
--
-- V1 only modelled merchant-customer charges (a tenant's customer pays
-- the tenant's sub-account, with a platform fee). The creative-services
-- marketplace and brand-package subscriptions flip the direction: the
-- merchant themselves pays Conddo, and the charge routes to our platform
-- account — no sub-account fan-out.
--
-- This migration adds:
--   - charge_kind column to make the routing explicit
--   - creative_request_id + brand_package_subscription_id columns
--   - relaxed exactly-one-of constraint covering all four kinds
--
-- Backfill: existing rows are tagged BOOKING_DEPOSIT when booking_id is
-- set, ORDER when order_id is set. The V1 invariant guarantees one is
-- always present.
-- =====================================================================

ALTER TABLE payments.payments
    ADD COLUMN charge_kind                   TEXT,
    ADD COLUMN creative_request_id           UUID,
    ADD COLUMN brand_package_subscription_id UUID;

UPDATE payments.payments
SET charge_kind = CASE
        WHEN booking_id IS NOT NULL THEN 'BOOKING_DEPOSIT'
        WHEN order_id   IS NOT NULL THEN 'ORDER'
    END;

ALTER TABLE payments.payments
    ALTER COLUMN charge_kind SET NOT NULL,
    ALTER COLUMN charge_kind SET DEFAULT 'BOOKING_DEPOSIT';

ALTER TABLE payments.payments
    DROP CONSTRAINT one_of_order_or_booking;

ALTER TABLE payments.payments
    ADD CONSTRAINT exactly_one_target CHECK (
        (CASE WHEN order_id                     IS NOT NULL THEN 1 ELSE 0 END)
      + (CASE WHEN booking_id                   IS NOT NULL THEN 1 ELSE 0 END)
      + (CASE WHEN creative_request_id          IS NOT NULL THEN 1 ELSE 0 END)
      + (CASE WHEN brand_package_subscription_id IS NOT NULL THEN 1 ELSE 0 END) = 1
    );

ALTER TABLE payments.payments
    ADD CONSTRAINT charge_kind_valid CHECK (
        charge_kind IN ('BOOKING_DEPOSIT', 'ORDER', 'CREATIVE_SERVICE', 'BRAND_PACKAGE')
    );

CREATE INDEX idx_payments_creative_request ON payments.payments (creative_request_id)
    WHERE creative_request_id IS NOT NULL;
CREATE INDEX idx_payments_brand_subscription ON payments.payments (brand_package_subscription_id)
    WHERE brand_package_subscription_id IS NOT NULL;
