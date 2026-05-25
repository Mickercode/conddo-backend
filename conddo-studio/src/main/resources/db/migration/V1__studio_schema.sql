-- =====================================================================
-- Conddo Studio / Jobs Board — schema (Infrastructure §6.3, §6.4, §12, §13).
-- Internal-staff tool: NO tenant RLS. Lives in its own studio/jobs schemas in
-- the platform database; Flyway (this service) owns these schemas. Array-ish
-- fields use JSONB (reliable Hibernate mapping). tenant_id is a SOFT reference
-- to the platform's tenants (no cross-service FK).
-- =====================================================================

-- ---- studio schema -------------------------------------------------------

CREATE TABLE studio.staff (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email         TEXT UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    full_name     TEXT NOT NULL,
    role          TEXT NOT NULL
                    CHECK (role IN ('DEVELOPER','DESIGNER','WRITER','QA_REVIEWER','TEAM_LEAD','ADMIN')),
    skills        JSONB NOT NULL DEFAULT '[]'::jsonb,   -- e.g. ["WEBSITE_BUILD","GRAPHIC_DESIGN"]
    is_active     BOOLEAN NOT NULL DEFAULT true,
    last_login_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE studio.job_types (
    id                TEXT PRIMARY KEY,
    display_name      TEXT NOT NULL,
    colour            TEXT NOT NULL DEFAULT '#7C5CBF',
    assigned_to_roles JSONB NOT NULL DEFAULT '[]'::jsonb,
    sla_hours         INTEGER NOT NULL,
    qa_required       BOOLEAN NOT NULL DEFAULT true,
    qa_checklist      JSONB NOT NULL DEFAULT '[]'::jsonb,
    is_active         BOOLEAN NOT NULL DEFAULT true,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO studio.job_types (id, display_name, colour, assigned_to_roles, sla_hours, qa_required, qa_checklist) VALUES
('WEBSITE_BUILD', 'Website Build', '#7C5CBF', '["DEVELOPER"]'::jsonb, 48, true,
 '[{"id":"no_placeholder","label":"No placeholder text anywhere","required":true},
   {"id":"logo_placed","label":"Logo correctly placed and sized","required":true},
   {"id":"brand_colours","label":"Brand colours applied consistently","required":true},
   {"id":"mobile_check","label":"Mobile view correct at 375px","required":true},
   {"id":"contact_accurate","label":"Contact details accurate","required":true},
   {"id":"cta_present","label":"CTA buttons present and labelled","required":true},
   {"id":"design_standard","label":"Meets design standard library","required":true},
   {"id":"copy_natural","label":"Copy reads naturally","required":true}]'::jsonb),
('WEBSITE_REVISION', 'Website Revision', '#A07FD4', '["DEVELOPER"]'::jsonb, 24, true, '[]'::jsonb),
('GRAPHIC_DESIGN',  'Graphic Design',   '#F59E0B', '["DESIGNER"]'::jsonb,  24, true, '[]'::jsonb),
('AD_CREATIVE',     'Ad Creative',      '#EF4444', '["DESIGNER"]'::jsonb,  12, true, '[]'::jsonb),
('BRAND_KIT',       'Brand Kit',        '#22C55E', '["DESIGNER"]'::jsonb,  72, true, '[]'::jsonb),
('CONTENT_WRITING', 'Content Writing',  '#3B82F6', '["WRITER"]'::jsonb,    24, true, '[]'::jsonb);

CREATE SEQUENCE studio.job_number_seq START 1000;

CREATE TABLE studio.jobs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_number      TEXT UNIQUE NOT NULL,
    job_type_id     TEXT NOT NULL REFERENCES studio.job_types (id),
    tenant_id       UUID,                      -- soft reference to platform tenants (no FK)
    title           TEXT NOT NULL,
    brief           JSONB NOT NULL DEFAULT '{}'::jsonb,
    assets          JSONB NOT NULL DEFAULT '[]'::jsonb,
    status          TEXT NOT NULL DEFAULT 'QUEUED'
                      CHECK (status IN ('QUEUED','AVAILABLE','ASSIGNED','IN_PROGRESS',
                                        'SUBMITTED','IN_REVIEW','REVISION','APPROVED',
                                        'DELIVERED','ESCALATED','CANCELLED')),
    assigned_to     UUID REFERENCES studio.staff (id),
    assigned_at     TIMESTAMPTZ,
    started_at      TIMESTAMPTZ,
    submitted_at    TIMESTAMPTZ,
    approved_at     TIMESTAMPTZ,
    delivered_at    TIMESTAMPTZ,
    sla_deadline    TIMESTAMPTZ NOT NULL,
    sla_extended_by INTEGER NOT NULL DEFAULT 0,
    revision_count  INTEGER NOT NULL DEFAULT 0,
    studio_url      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_studio_jobs_status ON studio.jobs (status, sla_deadline);
CREATE INDEX idx_studio_jobs_assigned ON studio.jobs (assigned_to, status);
CREATE INDEX idx_studio_jobs_type ON studio.jobs (job_type_id, status);

CREATE TABLE studio.job_activity (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id     UUID NOT NULL REFERENCES studio.jobs (id) ON DELETE CASCADE,
    staff_id   UUID REFERENCES studio.staff (id),
    action     TEXT NOT NULL,
    detail     TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_studio_job_activity ON studio.job_activity (job_id, created_at DESC);

CREATE TABLE studio.qa_reviews (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id         UUID NOT NULL REFERENCES studio.jobs (id),
    reviewer_id    UUID NOT NULL REFERENCES studio.staff (id),
    outcome        TEXT NOT NULL CHECK (outcome IN ('APPROVED','REVISION')),
    checklist      JSONB NOT NULL DEFAULT '{}'::jsonb,
    reviewer_notes TEXT,
    positive_notes TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_studio_qa_reviews ON studio.qa_reviews (job_id, created_at DESC);

-- ---- jobs schema (board UI layer) ----------------------------------------

CREATE TABLE jobs.staff_refresh_tokens (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    staff_id   UUID NOT NULL REFERENCES studio.staff (id) ON DELETE CASCADE,
    token_hash TEXT UNIQUE NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_staff_refresh_tokens_staff ON jobs.staff_refresh_tokens (staff_id);

CREATE TABLE jobs.notifications (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    staff_id   UUID NOT NULL REFERENCES studio.staff (id),
    type       TEXT NOT NULL,
    title      TEXT NOT NULL,
    message    TEXT NOT NULL,
    job_id     UUID REFERENCES studio.jobs (id),
    is_read    BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_jobs_notifications ON jobs.notifications (staff_id, is_read, created_at DESC);

CREATE TABLE jobs.staff_performance (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    staff_id            UUID NOT NULL REFERENCES studio.staff (id),
    period_month        DATE NOT NULL,
    jobs_completed      INTEGER NOT NULL DEFAULT 0,
    jobs_target         INTEGER NOT NULL DEFAULT 15,
    first_pass_qa_rate  NUMERIC(5,2) NOT NULL DEFAULT 0,
    avg_build_minutes   INTEGER NOT NULL DEFAULT 0,
    sla_compliance_rate NUMERIC(5,2) NOT NULL DEFAULT 100,
    revision_count      INTEGER NOT NULL DEFAULT 0,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (staff_id, period_month)
);
