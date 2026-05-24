-- =====================================================================
-- V3 — Authentication schema (PRD §6.2 / §12.1)
--
-- Run by Flyway as the OWNER role. Adds what the auth subsystem needs:
-- account-lockout tracking on users, refresh-token rotation + family
-- invalidation on refresh_tokens, and a password-reset token store.
-- Privileges and the RLS rationale for these objects live in V4.
-- =====================================================================

-- USERS — account lockout: N failed logins -> timed lockout, exponential backoff.
ALTER TABLE users
    ADD COLUMN failed_login_attempts INT         NOT NULL DEFAULT 0,
    ADD COLUMN locked_until          TIMESTAMPTZ;

-- REFRESH TOKENS — opaque "selector.verifier" tokens (PRD §6.2):
--   selector    : indexed lookup key (the verifier is never used to query)
--   token_hash  : BCrypt(verifier) — the V1 column, now holds the verifier hash
--   family_id   : all tokens descended from one login; presenting a REVOKED
--                 token revokes the whole family (reuse detection)
--   replaced_by : the token this one was rotated into (rotation audit trail)
-- The table is empty after Phase 0, so NOT NULL columns add cleanly.
ALTER TABLE refresh_tokens
    ADD COLUMN tenant_id      UUID NOT NULL REFERENCES tenants(id)        ON DELETE CASCADE,
    ADD COLUMN family_id      UUID NOT NULL,
    ADD COLUMN selector       TEXT NOT NULL,
    ADD COLUMN replaced_by    UUID REFERENCES refresh_tokens(id)          ON DELETE SET NULL,
    ADD COLUMN revoked_reason TEXT;
CREATE UNIQUE INDEX uq_refresh_tokens_selector ON refresh_tokens (selector);
CREATE INDEX        idx_refresh_tokens_family   ON refresh_tokens (family_id);
CREATE INDEX        idx_refresh_tokens_tenant   ON refresh_tokens (tenant_id);

-- PASSWORD RESET TOKENS — same selector/verifier scheme, single-use, short-lived.
CREATE TABLE password_reset_tokens (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID        NOT NULL REFERENCES users(id)   ON DELETE CASCADE,
    tenant_id  UUID        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    selector   TEXT        NOT NULL UNIQUE,
    token_hash TEXT        NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_password_reset_user ON password_reset_tokens (user_id);

-- PLATFORM TENANT — sentinel home for SUPER_ADMIN users (Handel Cores staff),
-- who belong to no single business. Fixed id so application code can reference
-- it. Seeding only the tenant row is safe in every environment; SUPER_ADMIN
-- accounts are provisioned separately — we never ship default credentials.
INSERT INTO tenants (id, name, slug, status)
VALUES ('00000000-0000-0000-0000-000000000001', 'Conddo Platform', 'platform', 'ACTIVE');
