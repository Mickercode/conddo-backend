-- =====================================================================
-- Conddo Payments — V1 schema (ACTION_LIST §7a).
-- Standalone payments service: own deployment, own URL, own schema, but
-- shares the platform Postgres instance (cheaper, single backup). Runs
-- as conddo_owner — RLS doesn't apply, access is gated in service code
-- by tenantId in the inbound JWT or X-Payments-Service-Token header.
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS payments;

-- One RoutePay sub-account per tenant. Provisioned by an @Async listener
-- on TenantActivatedEvent (conddo-api → conddo-payments service-token call),
-- so signup never blocks on RoutePay being up.
CREATE TABLE payments.tenant_accounts (
    tenant_id                 UUID PRIMARY KEY,            -- platform's tenants.id (soft FK)
    tenant_slug               TEXT NOT NULL,
    routepay_subaccount_id    TEXT UNIQUE,                 -- null while DEPOSIT_PENDING / PROVISIONING_FAILED
    business_name             TEXT NOT NULL,
    contact_email             TEXT NOT NULL,
    settlement_bank_name      TEXT,                         -- optional at signup; from Settings → Payments
    settlement_bank_account   TEXT,                         -- 10 digits NUBAN; masked at read time
    settlement_account_holder TEXT,
    status                    TEXT NOT NULL DEFAULT 'DEPOSIT_PENDING'
                                CHECK (status IN ('DEPOSIT_PENDING','ACTIVE','SUSPENDED','PROVISIONING_FAILED')),
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_tenant_accounts_status ON payments.tenant_accounts(status);

-- One row per payment attempt. Webhook receipts update the row idempotently
-- (we re-write the same row when RoutePay re-posts a webhook). Terminal
-- statuses (PAID / FAILED / REFUNDED / EXPIRED) are never reverted.
CREATE TABLE payments.payments (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                UUID NOT NULL,                -- soft reference; no FK across services
    tenant_slug              TEXT NOT NULL,                -- denormalised for return URL building
    order_id                 UUID,                          -- mutually exclusive with booking_id
    booking_id               UUID,
    customer_id              UUID,                          -- denormalised — platform's customer
    customer_email           TEXT NOT NULL,
    customer_name            TEXT NOT NULL,
    description              TEXT,

    -- RoutePay
    routepay_reference       TEXT NOT NULL UNIQUE,         -- our reference sent to RoutePay
    routepay_transaction_ref TEXT UNIQUE,                   -- their reference after init succeeds
    payment_url              TEXT,                          -- hosted checkout URL (RoutePay's link)
    status                   TEXT NOT NULL DEFAULT 'PENDING'
                                CHECK (status IN ('PENDING','PAID','FAILED','REFUNDED','EXPIRED')),

    -- Money — always integer minor units (kobo). NGN at V1; the column is
    -- there so a multi-currency push later doesn't break existing rows.
    amount_kobo              BIGINT NOT NULL CHECK (amount_kobo > 0),
    currency                 TEXT NOT NULL DEFAULT 'NGN',
    fee_kobo                 BIGINT,
    paid_at                  TIMESTAMPTZ,
    failure_reason           TEXT,

    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    raw_webhook_payload      JSONB,                         -- last verified webhook body (forensics)

    -- Either an order or a booking — never both, never neither.
    CONSTRAINT one_of_order_or_booking
        CHECK ((order_id IS NULL) <> (booking_id IS NULL))
);
CREATE INDEX idx_payments_tenant_status ON payments.payments(tenant_id, status, created_at DESC);
CREATE INDEX idx_payments_customer ON payments.payments(customer_id);
CREATE INDEX idx_payments_routepay_txn ON payments.payments(routepay_transaction_ref)
    WHERE routepay_transaction_ref IS NOT NULL;

-- Webhook idempotency log. RoutePay retries on transient errors; we use this
-- to short-circuit a duplicate event without re-applying the state change.
CREATE TABLE payments.webhook_events (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    routepay_event_id  TEXT UNIQUE,                         -- their event id when present
    signature          TEXT,                                 -- raw header for forensics
    payload_sha256     TEXT NOT NULL,                        -- fallback dedupe key
    payment_id         UUID REFERENCES payments.payments(id),
    received_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at       TIMESTAMPTZ,
    error              TEXT
);
CREATE INDEX idx_webhook_events_payload_hash ON payments.webhook_events(payload_sha256);
