-- =====================================================================
-- V37 — Pharmacy Module Spec v2 §12D: Reminders.
--
-- Pharmacist-defined SMS reminders for customers. Lifecycle:
--   SCHEDULED → SENT (or FAILED if Brevo errored)
--           \-> CANCELLED (manual)
--
-- On SENT, if the recurrence column is set and recurrence_end has
-- not passed, the scheduler inserts a NEW SCHEDULED row at the next
-- occurrence — preserves an audit trail of every send while keeping
-- a single live row queued at any time.
-- =====================================================================

CREATE TABLE pharmacy_reminders (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID         NOT NULL REFERENCES tenants (id),
    -- No FKs on customer_id / product_id — reminders are an immutable
    -- audit log and must outlive deletes of either.
    customer_id     UUID         NOT NULL,
    product_id      UUID,
    reminder_type   VARCHAR(30)  NOT NULL
        CHECK (reminder_type IN ('REFILL_DUE', 'DRUG_USAGE', 'FOLLOW_UP', 'REFILL_OFFER', 'CUSTOM')),
    message         TEXT         NOT NULL,
    scheduled_at    TIMESTAMPTZ  NOT NULL,
    recurrence      VARCHAR(20)
        CHECK (recurrence IN ('ONCE', 'DAILY', 'WEEKLY', 'MONTHLY')),
    recurrence_end  TIMESTAMPTZ,
    status          VARCHAR(20)  NOT NULL DEFAULT 'SCHEDULED'
        CHECK (status IN ('SCHEDULED', 'SENT', 'FAILED', 'CANCELLED')),
    sent_at         TIMESTAMPTZ,
    failure_reason  TEXT,
    created_by      UUID         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_reminders_tenant_status_due
    ON pharmacy_reminders (tenant_id, status, scheduled_at);
CREATE INDEX idx_reminders_status_due
    ON pharmacy_reminders (status, scheduled_at)
    WHERE status = 'SCHEDULED';
CREATE INDEX idx_reminders_tenant_customer
    ON pharmacy_reminders (tenant_id, customer_id, scheduled_at DESC);

GRANT SELECT, INSERT, UPDATE, DELETE ON pharmacy_reminders TO ${app_role};

ALTER TABLE pharmacy_reminders ENABLE ROW LEVEL SECURITY;

-- Tenant isolation + cross_tenant carve-out (V26 pattern) so the
-- hourly scheduler can walk every tenant's due rows AND update them
-- in place — sets `app.cross_tenant = true` before running.
CREATE POLICY tenant_isolation ON pharmacy_reminders
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true')
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true');
