-- =====================================================================
-- V16 — Marketing posts + campaigns (§11.8). Tenant business data → RLS per the
-- §3 checklist. Leads + connections follow in V17. Actual social publishing and
-- bulk email/SMS delivery are deferred (need the social OAuth / Notifications
-- send integrations); these tables model the schedule + stats.
-- =====================================================================

CREATE TABLE marketing_posts (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID        NOT NULL REFERENCES tenants (id),
    title        TEXT,
    content      TEXT,
    platforms    JSONB       NOT NULL DEFAULT '[]'::jsonb,  -- ["instagram","facebook",...]
    media_ids    JSONB       NOT NULL DEFAULT '[]'::jsonb,
    scheduled_at TIMESTAMPTZ,
    status       TEXT        NOT NULL DEFAULT 'scheduled',  -- draft | scheduled | published
    published_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_marketing_posts_tenant_sched ON marketing_posts (tenant_id, scheduled_at);

CREATE TABLE marketing_campaigns (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID        NOT NULL REFERENCES tenants (id),
    name          TEXT        NOT NULL,
    type          TEXT        NOT NULL,                      -- email | sms
    status        TEXT        NOT NULL DEFAULT 'draft',      -- draft | scheduled | sending | sent
    content       TEXT,
    audience_size INTEGER     NOT NULL DEFAULT 0,
    sent          INTEGER     NOT NULL DEFAULT 0,
    delivered     INTEGER     NOT NULL DEFAULT 0,
    opened        INTEGER     NOT NULL DEFAULT 0,
    clicked       INTEGER     NOT NULL DEFAULT 0,
    scheduled_at  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_marketing_campaigns_tenant ON marketing_campaigns (tenant_id, type, status);

GRANT SELECT, INSERT, UPDATE, DELETE ON marketing_posts     TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON marketing_campaigns TO ${app_role};

ALTER TABLE marketing_posts     ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketing_campaigns ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON marketing_posts
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY tenant_isolation ON marketing_campaigns
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
