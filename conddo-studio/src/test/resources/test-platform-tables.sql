-- Minimal mirror of conddo-api's V1 platform schema (Studio reads these via
-- the PlatformTenant / PlatformUser entities — §23.2). The Studio test
-- container only runs Studio's own migrations, so this seeds the columns
-- Hibernate validates at boot. Real platform deploys (conddo-api's Flyway)
-- own the production schema.

CREATE TABLE IF NOT EXISTS public.tenants (
    id                   UUID PRIMARY KEY,
    name                 TEXT NOT NULL,
    slug                 TEXT NOT NULL UNIQUE,
    vertical_id          TEXT,
    plan_id              TEXT,
    custom_domain        TEXT,
    status               TEXT NOT NULL DEFAULT 'ACTIVE',
    website_status       TEXT NOT NULL DEFAULT 'NOT_STARTED',
    website_published_at TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS public.users (
    id             UUID PRIMARY KEY,
    tenant_id      UUID NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    email          TEXT NOT NULL,
    password_hash  TEXT NOT NULL,
    full_name      TEXT,
    role           TEXT NOT NULL,
    phone          TEXT,
    is_active      BOOLEAN NOT NULL DEFAULT TRUE,
    phone_verified BOOLEAN NOT NULL DEFAULT FALSE,
    last_login_at  TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    google_sub     TEXT
);

-- Site Registration admin (SITE_REGISTRATION_ADMIN_SPEC) — Studio reads + writes
-- this table via PlatformTenantSite. Real platform deploys (conddo-api V25)
-- own the production schema; this seed is just enough for Hibernate's
-- ddl-auto:validate at Studio test boot.
CREATE TABLE IF NOT EXISTS public.tenant_sites (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID         NOT NULL REFERENCES public.tenants (id),
    subdomain         VARCHAR(100) UNIQUE,
    custom_domain     VARCHAR(255) UNIQUE,
    hosting_provider  VARCHAR(50),
    site_type         VARCHAR(50),
    api_key_hash      VARCHAR(255) NOT NULL,
    api_key_last4     VARCHAR(4)   NOT NULL,
    is_active         BOOLEAN      NOT NULL DEFAULT false,
    qa_approved       BOOLEAN      NOT NULL DEFAULT false,
    qa_approved_by    UUID,
    qa_approved_at    TIMESTAMPTZ,
    submitted_url     VARCHAR(500),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_tenant_sites_tenant
    ON public.tenant_sites (tenant_id);

-- Phase 13b's mutators revoke refresh tokens for suspended tenants and
-- deactivated users. The Studio service writes UPDATEs against this table.
CREATE TABLE IF NOT EXISTS public.refresh_tokens (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    tenant_id      UUID NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    family_id      UUID NOT NULL,
    selector       TEXT NOT NULL UNIQUE,
    token_hash     TEXT NOT NULL,
    expires_at     TIMESTAMPTZ NOT NULL,
    revoked_at     TIMESTAMPTZ,
    revoked_reason TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
