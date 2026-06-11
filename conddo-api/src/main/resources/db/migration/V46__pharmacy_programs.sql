-- =====================================================================
-- V46 — Pharmacy Roadmap Beta 3: Drug Programs (chronic care
-- subscriptions). HANDOFF_2026-06-11 §3 + §8.
--
-- Tenant defines a program (Diabetes Care, Hypertension Care, ...) that
-- bundles medication + reminders + monthly price. Customer subscribes
-- via Paystack; enrolment lifecycle is driven by the Paystack webhook.
-- =====================================================================

CREATE TABLE pharmacy_programs (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID         NOT NULL REFERENCES tenants (id),
    name            VARCHAR(150) NOT NULL,
    description     TEXT,
    target_condition VARCHAR(120),
    duration_months INTEGER,                            -- null = ongoing
    monthly_price   NUMERIC(12, 2) NOT NULL CHECK (monthly_price > 0),
    is_active       BOOLEAN      NOT NULL DEFAULT true,
    -- Two booleans per FE handoff §3: is_active is the tenant-side
    -- "this program is current" toggle, is_published is the public-
    -- website visibility toggle.
    is_published    BOOLEAN      NOT NULL DEFAULT false,
    paystack_plan_code VARCHAR(64),
    created_by      UUID         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_programs_tenant_published
    ON pharmacy_programs (tenant_id, is_published, is_active);

CREATE TABLE pharmacy_program_items (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID         NOT NULL REFERENCES tenants (id),
    program_id      UUID         NOT NULL REFERENCES pharmacy_programs (id) ON DELETE CASCADE,
    product_id      UUID         NOT NULL,
    quantity        INTEGER      NOT NULL DEFAULT 1 CHECK (quantity > 0),
    frequency       VARCHAR(20)  NOT NULL DEFAULT 'MONTHLY'
        CHECK (frequency IN ('DAILY', 'WEEKLY', 'MONTHLY', 'QUARTERLY'))
);

CREATE INDEX idx_program_items_program
    ON pharmacy_program_items (program_id);

CREATE TABLE pharmacy_program_enrollments (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID         NOT NULL REFERENCES tenants (id),
    program_id      UUID         NOT NULL REFERENCES pharmacy_programs (id),
    customer_id     UUID         NOT NULL,
    status          VARCHAR(30)  NOT NULL DEFAULT 'PENDING_PAYMENT'
        CHECK (status IN ('PENDING_PAYMENT', 'ACTIVE', 'PAUSED', 'COMPLETED', 'CANCELLED')),
    paystack_subscription_code VARCHAR(64),
    enrolled_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    next_billing_at TIMESTAMPTZ,
    ends_at         TIMESTAMPTZ,
    enrolled_by     UUID,         -- staff or null on customer self-enrol
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_enrollments_program_status
    ON pharmacy_program_enrollments (program_id, status);
CREATE INDEX idx_enrollments_tenant_customer
    ON pharmacy_program_enrollments (tenant_id, customer_id);

-- Append-only renewal ledger (HANDOFF §3 step 4). Populated by the
-- Paystack webhook on charge.success during the recurring lifecycle.
CREATE TABLE pharmacy_program_charges (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID         NOT NULL REFERENCES tenants (id),
    enrollment_id   UUID         NOT NULL REFERENCES pharmacy_program_enrollments (id) ON DELETE CASCADE,
    amount_kobo     BIGINT       NOT NULL,
    paystack_reference VARCHAR(80),
    charged_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_program_charges_enrollment
    ON pharmacy_program_charges (enrollment_id, charged_at DESC);

GRANT SELECT, INSERT, UPDATE, DELETE ON pharmacy_programs               TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON pharmacy_program_items          TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON pharmacy_program_enrollments    TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON pharmacy_program_charges        TO ${app_role};

ALTER TABLE pharmacy_programs            ENABLE ROW LEVEL SECURITY;
ALTER TABLE pharmacy_program_items       ENABLE ROW LEVEL SECURITY;
ALTER TABLE pharmacy_program_enrollments ENABLE ROW LEVEL SECURITY;
ALTER TABLE pharmacy_program_charges     ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON pharmacy_programs
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true'
                OR current_setting('app.public_resolver', true) = 'true')
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true');

CREATE POLICY tenant_isolation ON pharmacy_program_items
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true'
                OR current_setting('app.public_resolver', true) = 'true')
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true');

CREATE POLICY tenant_isolation ON pharmacy_program_enrollments
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true')
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true');

CREATE POLICY tenant_isolation ON pharmacy_program_charges
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true')
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true');

-- billing_paystack_transactions (V45) gets a purpose discriminator so
-- the same table can ledger plan upgrades and program enrolments. A
-- nullable enrollment_id lets the program webhook find the row. We
-- also relax the plan_id FK + NOT NULL so program enrolments (which
-- reference pharmacy_programs, not subscription_plans) can write to
-- the same table.
ALTER TABLE billing_paystack_transactions
    ADD COLUMN purpose       VARCHAR(30) NOT NULL DEFAULT 'PLAN_UPGRADE'
        CHECK (purpose IN ('PLAN_UPGRADE', 'PROGRAM_ENROLLMENT')),
    ADD COLUMN enrollment_id UUID;

ALTER TABLE billing_paystack_transactions
    DROP CONSTRAINT billing_paystack_transactions_plan_id_fkey;
ALTER TABLE billing_paystack_transactions
    ALTER COLUMN plan_id DROP NOT NULL;

CREATE INDEX idx_paystack_tx_enrollment
    ON billing_paystack_transactions (enrollment_id)
    WHERE enrollment_id IS NOT NULL;
