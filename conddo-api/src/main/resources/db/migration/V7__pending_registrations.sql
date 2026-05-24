-- =====================================================================
-- V7 — Phone-verified staged signup (PRD §6.2; OTP wizard).
--
-- pending_registrations holds an in-progress signup (the person's account
-- details + a hashed OTP) until the phone is verified and the wizard finishes,
-- at which point the tenant + admin user are created atomically. It is
-- pre-tenant, so — like staff_users — it has NO tenant_id and NO RLS; it is
-- looked up by its unguessable UUID id.
-- =====================================================================

CREATE TABLE pending_registrations (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    full_name        TEXT        NOT NULL,
    phone            TEXT        NOT NULL,
    email            TEXT        NOT NULL,
    password_hash    TEXT        NOT NULL,   -- BCrypt of the chosen password
    phone_verified   BOOLEAN     NOT NULL DEFAULT FALSE,
    otp_hash         TEXT        NOT NULL,   -- BCrypt of the current 4-digit code
    otp_expires_at   TIMESTAMPTZ NOT NULL,
    otp_attempts     INT         NOT NULL DEFAULT 0,   -- failed verify attempts on the current code
    otp_sent_count   INT         NOT NULL DEFAULT 1,   -- total codes sent (resend cap)
    last_otp_sent_at TIMESTAMPTZ NOT NULL,             -- resend cooldown anchor
    completed_at     TIMESTAMPTZ,                       -- set when the tenant is created
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_pending_registrations_phone ON pending_registrations (phone);

GRANT SELECT, INSERT, UPDATE, DELETE ON pending_registrations TO ${app_role};

-- Real users carry their phone-verification state (set true when signup completes).
ALTER TABLE users ADD COLUMN phone_verified BOOLEAN NOT NULL DEFAULT FALSE;
