-- =====================================================================
-- V47 — Point of Sale, Phase 1 (cash + transfer MVP).
--
-- Cashier opens a shift (pos_session) with a cash float, runs sales
-- (pos_sales + pos_sale_items + pos_payments), closes the shift with
-- a counted cash amount → variance is logged. Each completed sale
-- writes one SALE_POS stock movement per line item via the existing
-- StockMovementService chokepoint.
--
-- Card-in-store + discounts + refunds = Phase 2. Rx gate = Phase 3.
-- =====================================================================

CREATE TABLE pos_sessions (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID         NOT NULL REFERENCES tenants (id),
    cashier_id      UUID         NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN', 'CLOSED')),
    opening_float   NUMERIC(12, 2) NOT NULL DEFAULT 0.00 CHECK (opening_float >= 0),
    counted_cash    NUMERIC(12, 2),
    expected_cash   NUMERIC(12, 2),
    cash_variance   NUMERIC(12, 2),
    notes           TEXT,
    opened_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    closed_at       TIMESTAMPTZ
);

-- Exactly one OPEN session per (tenant, cashier).
CREATE UNIQUE INDEX uq_pos_sessions_open_cashier
    ON pos_sessions (tenant_id, cashier_id)
    WHERE status = 'OPEN';

CREATE INDEX idx_pos_sessions_tenant_opened
    ON pos_sessions (tenant_id, opened_at DESC);

-- Daily-counter table so sale_number generation is atomic across
-- concurrent cashiers. ON CONFLICT DO UPDATE ... RETURNING gives us
-- the next seq with no race.
CREATE TABLE pos_sale_counters (
    tenant_id       UUID         NOT NULL REFERENCES tenants (id),
    sale_date       DATE         NOT NULL,
    next_seq        INTEGER      NOT NULL DEFAULT 1,
    PRIMARY KEY (tenant_id, sale_date)
);

CREATE TABLE pos_sales (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID         NOT NULL REFERENCES tenants (id),
    session_id      UUID         NOT NULL REFERENCES pos_sessions (id),
    cashier_id      UUID         NOT NULL,
    customer_id     UUID,
    status          VARCHAR(20)  NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN', 'COMPLETED', 'VOIDED')),
    sale_number     VARCHAR(40)  NOT NULL,
    subtotal        NUMERIC(12, 2) NOT NULL DEFAULT 0.00 CHECK (subtotal >= 0),
    total           NUMERIC(12, 2) NOT NULL DEFAULT 0.00 CHECK (total >= 0),
    opened_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,
    voided_at       TIMESTAMPTZ
);

CREATE UNIQUE INDEX uq_pos_sales_number
    ON pos_sales (tenant_id, sale_number);
CREATE INDEX idx_pos_sales_session_status
    ON pos_sales (session_id, status);
CREATE INDEX idx_pos_sales_customer
    ON pos_sales (customer_id) WHERE customer_id IS NOT NULL;

CREATE TABLE pos_sale_items (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID         NOT NULL REFERENCES tenants (id),
    sale_id         UUID         NOT NULL REFERENCES pos_sales (id) ON DELETE CASCADE,
    product_id      UUID         NOT NULL,
    product_name    VARCHAR(200) NOT NULL,
    sku             VARCHAR(80),
    qty             INTEGER      NOT NULL CHECK (qty > 0),
    unit_price      NUMERIC(12, 2) NOT NULL CHECK (unit_price >= 0),
    line_total      NUMERIC(12, 2) NOT NULL CHECK (line_total >= 0),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- One row per product per sale — the service folds same-product adds
-- into a qty increment rather than duplicate lines.
CREATE UNIQUE INDEX uq_pos_sale_items_product
    ON pos_sale_items (sale_id, product_id);
CREATE INDEX idx_pos_sale_items_product
    ON pos_sale_items (product_id);

CREATE TABLE pos_payments (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID         NOT NULL REFERENCES tenants (id),
    sale_id         UUID         NOT NULL REFERENCES pos_sales (id) ON DELETE CASCADE,
    method          VARCHAR(30)  NOT NULL
        CHECK (method IN ('CASH', 'TRANSFER', 'CARD')),
    amount          NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    reference       VARCHAR(120),
    paid_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_pos_payments_sale ON pos_payments (sale_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON pos_sessions       TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON pos_sale_counters  TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON pos_sales          TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON pos_sale_items     TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON pos_payments       TO ${app_role};

ALTER TABLE pos_sessions      ENABLE ROW LEVEL SECURITY;
ALTER TABLE pos_sale_counters ENABLE ROW LEVEL SECURITY;
ALTER TABLE pos_sales         ENABLE ROW LEVEL SECURITY;
ALTER TABLE pos_sale_items    ENABLE ROW LEVEL SECURITY;
ALTER TABLE pos_payments      ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON pos_sessions
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true')
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true');

CREATE POLICY tenant_isolation ON pos_sale_counters
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true')
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true');

CREATE POLICY tenant_isolation ON pos_sales
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true')
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true');

CREATE POLICY tenant_isolation ON pos_sale_items
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true')
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true');

CREATE POLICY tenant_isolation ON pos_payments
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true')
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true');
