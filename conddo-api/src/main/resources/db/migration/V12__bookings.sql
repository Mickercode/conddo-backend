-- =====================================================================
-- V12 — Bookings (§11.5): appointments + per-tenant scheduling config.
--
-- Appointments (`bookings`) are tenant business data → RLS, per the §3
-- checklist. Scheduling *config* (working hours, slot/buffer, the self-book
-- link) lives on `tenants` instead: it is tenant configuration (like plan_id /
-- custom_domain, already there) and must be resolvable by the PUBLIC self-book
-- endpoint without a tenant context — so it cannot sit behind RLS.
-- =====================================================================

ALTER TABLE tenants
    ADD COLUMN working_hours         JSONB,
    ADD COLUMN slot_duration_minutes INTEGER NOT NULL DEFAULT 60,
    ADD COLUMN buffer_minutes        INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN booking_link_slug     TEXT,
    ADD COLUMN booking_link_enabled  BOOLEAN NOT NULL DEFAULT true;

-- Seed the self-book slug from the (unique) tenant slug; it is regenerable later.
UPDATE tenants SET booking_link_slug = slug WHERE booking_link_slug IS NULL;
ALTER TABLE tenants ADD CONSTRAINT uq_tenants_booking_link_slug UNIQUE (booking_link_slug);

CREATE TABLE bookings (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID        NOT NULL REFERENCES tenants (id),
    customer_id   UUID        REFERENCES customers (id) ON DELETE SET NULL,
    customer_name TEXT,
    service       TEXT,
    starts_at     TIMESTAMPTZ NOT NULL,
    ends_at       TIMESTAMPTZ NOT NULL,
    mode          TEXT        NOT NULL DEFAULT 'in_person',  -- in_person | virtual
    status        TEXT        NOT NULL DEFAULT 'confirmed',  -- confirmed | pending | cancelled | completed
    amount        NUMERIC(14, 2) NOT NULL DEFAULT 0,
    notes         TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_bookings_tenant_start ON bookings (tenant_id, starts_at);
CREATE INDEX idx_bookings_customer ON bookings (customer_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON bookings TO ${app_role};

ALTER TABLE bookings ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON bookings
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
