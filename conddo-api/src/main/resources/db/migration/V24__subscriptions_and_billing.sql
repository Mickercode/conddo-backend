-- =====================================================================
-- Billing tiers — Phase 1 (BILLING_TIERS_SPEC.md).
--
-- Three new tables driving subscription state + feature gating, plus a
-- one-shot rename of legacy plan_ids on the `tenants` row (starter →
-- launcher, business → growth, pro → scaler). The matrix in
-- conddo-core stays on the old names internally (they're tier names,
-- not product names); a translator at the JWT/manifest boundary keeps
-- the existing logic working.
--
-- Plans + features are seeded here (Flyway-managed) rather than via a
-- service @PostConstruct so the data is reproducible across every
-- environment without a startup race.
-- =====================================================================

CREATE TABLE subscription_plans (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(50)  NOT NULL UNIQUE,        -- launcher | growth | scaler
    display_name    VARCHAR(50)  NOT NULL,
    monthly_price   INTEGER,                              -- Kobo; NULL for Scaler (custom-priced)
    quarterly_price INTEGER,                              -- Kobo; NULL for Scaler
    is_custom       BOOLEAN      NOT NULL DEFAULT false,
    is_active       BOOLEAN      NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- One row per tenant subscription. The partial unique index enforces "at
-- most one active row per tenant" — historical rows (status = 'expired'
-- or 'cancelled' and past expires_at) are kept for audit.
CREATE TABLE tenant_subscriptions (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID         NOT NULL REFERENCES tenants (id),
    plan_id         UUID         NOT NULL REFERENCES subscription_plans (id),
    billing_cycle   VARCHAR(20)  NOT NULL,                -- monthly | quarterly | custom
    status          VARCHAR(20)  NOT NULL,                -- trialing | active | grace | expired | cancelled
    amount_paid     INTEGER      NOT NULL DEFAULT 0,      -- Kobo
    started_at      TIMESTAMPTZ  NOT NULL,
    expires_at      TIMESTAMPTZ  NOT NULL,
    trial_ends_at   TIMESTAMPTZ,
    cancelled_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_tenant_active_sub
    ON tenant_subscriptions (tenant_id)
    WHERE status IN ('trialing', 'active', 'grace');

CREATE INDEX idx_tenant_subscriptions_expires
    ON tenant_subscriptions (expires_at)
    WHERE status IN ('trialing', 'active', 'grace');

CREATE TABLE plan_features (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id         UUID         NOT NULL REFERENCES subscription_plans (id),
    feature_key     VARCHAR(100) NOT NULL,
    feature_value   VARCHAR(255),                          -- 'true'/'false', integer, or 'unlimited'
    UNIQUE (plan_id, feature_key)
);

-- Runtime privileges. Billing is read by the JWT-mint path + BillingService;
-- both run with full tenant context (not RLS-scoped — billing decisions need
-- to see all subscriptions per tenant including historical for audit).
GRANT SELECT, INSERT, UPDATE, DELETE ON subscription_plans    TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_subscriptions  TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON plan_features         TO ${app_role};

-- ---- Catalog seed ------------------------------------------------------

INSERT INTO subscription_plans (name, display_name, monthly_price, quarterly_price, is_custom)
VALUES
    ('launcher', 'Launcher',   2000000,  5400000,  false),   -- ₦20,000/mo · ₦54,000/qtr
    ('growth',   'Growth',     4500000, 12000000,  false),   -- ₦45,000/mo · ₦120,000/qtr
    ('scaler',   'Scaler',    12000000,     NULL,  true)     -- ₦120,000/mo · custom quarterly
ON CONFLICT (name) DO NOTHING;

-- ---- Feature seed ------------------------------------------------------

WITH plan_ids AS (
    SELECT name, id FROM subscription_plans WHERE name IN ('launcher','growth','scaler')
),
seed (plan_name, feature_key, feature_value) AS (
    VALUES
        ('launcher', 'website',             'true'),
        ('launcher', 'custom_domain',       'false'),
        ('launcher', 'business_email',      'false'),
        ('launcher', 'order_management',    'false'),
        ('launcher', 'bookings',            'false'),
        ('launcher', 'email_campaigns',     'false'),
        ('launcher', 'sms_campaigns',       'false'),
        ('launcher', 'social_scheduler',    'false'),
        ('launcher', 'ad_management',       'false'),
        ('launcher', 'multi_location',      'false'),
        ('launcher', 'api_access',          'false'),
        ('launcher', 'advanced_analytics',  'false'),
        ('launcher', 'staff_accounts',      '2'),

        ('growth',   'website',             'true'),
        ('growth',   'custom_domain',       'true'),
        ('growth',   'business_email',      'true'),
        ('growth',   'order_management',    'true'),
        ('growth',   'bookings',            'true'),
        ('growth',   'email_campaigns',     'true'),
        ('growth',   'sms_campaigns',       'true'),
        ('growth',   'social_scheduler',    'true'),
        ('growth',   'ad_management',       'true'),
        ('growth',   'multi_location',      'false'),
        ('growth',   'api_access',          'false'),
        ('growth',   'advanced_analytics',  'false'),
        ('growth',   'staff_accounts',      '5'),

        ('scaler',   'website',             'true'),
        ('scaler',   'custom_domain',       'true'),
        ('scaler',   'business_email',      'true'),
        ('scaler',   'order_management',    'true'),
        ('scaler',   'bookings',            'true'),
        ('scaler',   'email_campaigns',     'true'),
        ('scaler',   'sms_campaigns',       'true'),
        ('scaler',   'social_scheduler',    'true'),
        ('scaler',   'ad_management',       'true'),
        ('scaler',   'multi_location',      'true'),
        ('scaler',   'api_access',          'true'),
        ('scaler',   'advanced_analytics',  'true'),
        ('scaler',   'staff_accounts',      'unlimited')
)
INSERT INTO plan_features (plan_id, feature_key, feature_value)
SELECT plan_ids.id, seed.feature_key, seed.feature_value
FROM seed JOIN plan_ids ON plan_ids.name = seed.plan_name
ON CONFLICT (plan_id, feature_key) DO NOTHING;

-- ---- Rename legacy plan_ids on tenants ---------------------------------
-- starter/business/pro → launcher/growth/scaler (existing test + dev tenants).
-- The VerticalToolMatrix keys stay on the OLD names — that's a tier axis,
-- not a product-name axis (translator lives in BillingService).
UPDATE tenants SET plan_id = 'launcher' WHERE plan_id = 'starter';
UPDATE tenants SET plan_id = 'growth'   WHERE plan_id = 'business';
UPDATE tenants SET plan_id = 'scaler'   WHERE plan_id = 'pro';
