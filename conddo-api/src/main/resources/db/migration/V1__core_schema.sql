-- =====================================================================
-- V1 — Core Platform schema (PRD §11.1 / §11.2, trimmed for Phase 0)
--
-- Run by Flyway as the OWNER role (conddo_owner). Tables are owned by it.
-- Every tenant-scoped table carries tenant_id + created_at, per the PRD's
-- universal pattern. RLS policies are added in V2.
-- =====================================================================

-- TENANTS — one row per business on Conddo.io. This is the tenant key itself,
-- so it is not tenant-scoped (no RLS); it is managed by signup / super-admin.
CREATE TABLE tenants (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name          TEXT        NOT NULL,
    slug          TEXT        NOT NULL UNIQUE,
    vertical_id   TEXT,
    plan_id       TEXT,
    custom_domain TEXT,
    status        TEXT        NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- USERS — platform users, scoped to a tenant (except SUPER_ADMIN, handled later).
CREATE TABLE users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    email         TEXT        NOT NULL,
    password_hash TEXT        NOT NULL,
    full_name     TEXT,
    role          TEXT        NOT NULL DEFAULT 'TENANT_ADMIN',
    phone         TEXT,
    is_active     BOOLEAN     NOT NULL DEFAULT TRUE,
    last_login_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, email)
);
CREATE INDEX idx_users_tenant ON users (tenant_id);

-- REFRESH TOKENS — opaque, hashed, one family per login (PRD §6.2 / §12.1).
CREATE TABLE refresh_tokens (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash TEXT        NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);

-- AUDIT LOG — every sensitive action with before/after state (PRD §6, §12.5).
CREATE TABLE audit_log (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID,
    user_id       UUID,
    action        TEXT        NOT NULL,
    resource_type TEXT,
    resource_id   UUID,
    ip_address    INET,
    user_agent    TEXT,
    before_state  JSONB,
    after_state   JSONB,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_tenant ON audit_log (tenant_id);

-- CUSTOMERS (CRM) — tenant-scoped. Used to demonstrate RLS isolation.
CREATE TABLE customers (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    full_name     TEXT         NOT NULL,
    email         TEXT,
    phone         TEXT,
    notes         TEXT,
    total_spent   NUMERIC(14,2) NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_customers_tenant ON customers (tenant_id);
