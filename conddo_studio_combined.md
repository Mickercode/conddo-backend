# Conddo Studio — Internal Operations Platform

> **What it is:** The internal tool used by the Conddo.io production team to build customer websites, manage design and content jobs, review quality, and monitor operations.
>
> **Who uses it:** Website developers, graphic designers, content writers, QA reviewers, and the team lead.
>
> **What it is not:** A customer-facing product. Business owners never see this. It lives at `studio.conddo.io`.
>
> **Version:** 1.0 · Handel Cores · 2026 · Confidential

---

## Table of Contents

1. [System Overview](#1-system-overview)
2. [Architecture Decision](#2-architecture-decision)
3. [Tech Stack](#3-tech-stack)
4. [Project Structure](#4-project-structure)
5. [Database Schema](#5-database-schema)
6. [Authentication](#6-authentication)
7. [Job System](#7-job-system)
8. [AI Assistant Layer](#8-ai-assistant-layer)
9. [Asset Management](#9-asset-management)
10. [QA System](#10-qa-system)
11. [Notifications and Real-Time Updates](#11-notifications-and-real-time-updates)
12. [SLA Engine](#12-sla-engine)
13. [Design Standard Library](#13-design-standard-library)
14. [Performance Tracking](#14-performance-tracking)
15. [REST API Reference](#15-rest-api-reference)
16. [Frontend Structure](#16-frontend-structure)
17. [Environment Variables](#17-environment-variables)
18. [Docker Configuration](#18-docker-configuration)
19. [Implementation Sequence](#19-implementation-sequence)
20. ~~Implementation Rules for Agents~~ → moved to §23
21. [Website Builder API](#21-website-builder-api)   *(post-V1)*
22. [Job Export & Import](#22-job-export--import)   *(post-V1)*
23. [Implementation Rules for Agents](#23-implementation-rules-for-agents)

---

## 1. System Overview

Conddo Studio combines two things that were previously designed separately:

- **Conddo Studio** — the website builder environment where developers build customer websites
- **Jobs Board** — the operations layer where all work is assigned, tracked, reviewed, and closed

They are one system. The Jobs Board is the shell. Conddo Studio is the tool inside a job. A developer opens a job, reads the business brief, opens Studio to build the website, submits, and the QA reviewer reviews it — all within the same application.

### The Workflow

```
PLATFORM EVENT                    CONDDO STUDIO
─────────────────────────────────────────────────────────
Business signs up
  ↓
TenantActivated event fires
  ↓
Studio receives event via Redis ──► Creates job automatically
                                    Job enters AVAILABLE queue
                                         ↓
                                    Developer sees job on dashboard
                                    Claims it (SLA clock starts)
                                         ↓
                                    Developer opens job brief
                                    Opens website builder (Studio)
                                    AI assistant generates copy
                                    Developer builds, previews
                                         ↓
                                    Pre-submission checks run
                                    AI QA scan runs
                                    Developer submits with notes
                                         ↓
                                    QA reviewer sees job in queue
                                    Opens split-screen review
                                    Completes checklist
                                         ↓
                              PASS → Business owner notified
                              FAIL → Returned to developer
                                         ↓
                                    Job closed → DELIVERED
```

### Job Types

```
WEBSITE_BUILD     48h SLA   Developer   Auto-created on signup
WEBSITE_REVISION  24h SLA   Developer   Created by platform on revision request
GRAPHIC_DESIGN    24h SLA   Designer    Created manually by admin
AD_CREATIVE       12h SLA   Designer    Created manually or by ads module
BRAND_KIT         72h SLA   Designer    Created manually
CONTENT_WRITING   24h SLA   Writer      Created manually
```

### Job Lifecycle

```
QUEUED → AVAILABLE → ASSIGNED → IN_PROGRESS → SUBMITTED
                                                    ↓
                              REVISION ←──── IN_REVIEW
                                  ↓               ↓
                              (back to          APPROVED
                            IN_PROGRESS)           ↓
                                               DELIVERED
                                    (at any point if urgent)
                                               ESCALATED
```

---

## 2. Architecture Decision

### One Service, Two Interfaces

Conddo Studio is a single Spring Boot backend serving two React frontends:

```
studio.conddo.io
    ├── /           → Jobs Board (list, claim, track all jobs)
    ├── /job/:id    → Job detail + brief
    ├── /build/:id  → Studio builder (website build interface)
    ├── /qa         → QA review queue
    ├── /qa/:id     → QA review screen
    └── /admin      → Operations dashboard (team lead only)
```

Everything is one React app with role-based routing. One backend. One database schema. One deployment.

### Why Not Separate Services

Separating them creates:
- Two authentication systems for the same users
- Two database connections reading the same tables
- Two deployments to maintain
- Network calls between services for data that lives in the same schema
- Two sets of SSE connections for the same real-time events

None of that complexity is justified. They share every data model. Combine them.

### Separation from Main Platform

Conddo Studio is completely separate from the main Conddo.io platform backend. It does not share JWT tokens with tenant users. It has its own staff auth. It communicates with the main platform only to:

1. Receive `TenantActivated` events via Redis (to auto-create website build jobs)
2. Fetch tenant business briefs via the internal API when a job is opened
3. Notify the platform when a website is approved and ready

---

## 3. Tech Stack

### Backend

```
Language:       Java 21
Framework:      Spring Boot 3.x
Security:       Spring Security 6 (custom JWT — separate from platform JWT)
Database:       PostgreSQL 16 — studio schema within conddo database
ORM:            Spring Data JPA + Hibernate
Migrations:     Flyway
Cache:          Redis 7 (shared with main platform)
Object Storage: MinIO (conddo-studio bucket)
Job Queue:      Spring @Scheduled + Redis sorted sets for SLA monitoring
Real-time:      Server-Sent Events (SSE) via Spring SseEmitter
AI:             Claude API (Anthropic) — copy generation, QA scan, image ranking
Build:          Maven
Port:           8083
```

### Frontend

```
Framework:      Next.js 14 (App Router)
Language:       TypeScript
Styling:        Tailwind CSS
Components:     shadcn/ui
State:          Zustand
Data fetching:  TanStack Query
Forms:          React Hook Form + Zod
Real-time:      EventSource (SSE client)
Icons:          Lucide React
Charts:         Recharts
Drag and drop:  dnd-kit (kanban board)
Build:          Vite (for local dev) / Next.js (production)
Theme:          Dark mode — see design system below
```

### Design System (Dark Mode)

```
Background:       #0F1117
Surface:          #1A1D27
Surface 2:        #252836
Border:           #2E3347
Border light:     #3A3F55

Text primary:     #F1F5F9
Text secondary:   #94A3B8
Text muted:       #64748B

Accent:           #7C5CBF
Accent hover:     #6A4DAD
Accent bg:        #1E1630

Success:          #22C55E
Success bg:       #052E16
Warning:          #F59E0B
Warning bg:       #1C1007
Danger:           #EF4444
Danger bg:        #1C0505
Info:             #3B82F6
Info bg:          #0A1628

Font:             Inter
Mono:             Geist Mono (all times, IDs, metrics, code)
```

---

## 4. Project Structure

```
conddo-studio/
│
├── backend/                          ← Spring Boot service
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── java/io/conddo/studio/
│       │   │   │
│       │   │   ├── StudioApplication.java
│       │   │   │
│       │   │   ├── config/
│       │   │   │   ├── SecurityConfig.java
│       │   │   │   ├── RedisConfig.java
│       │   │   │   ├── MinioConfig.java
│       │   │   │   └── ClaudeConfig.java
│       │   │   │
│       │   │   ├── auth/
│       │   │   │   ├── AuthController.java
│       │   │   │   ├── AuthService.java
│       │   │   │   ├── JwtService.java
│       │   │   │   ├── StaffDetails.java
│       │   │   │   └── JwtAuthFilter.java
│       │   │   │
│       │   │   ├── staff/
│       │   │   │   ├── StaffController.java
│       │   │   │   ├── StaffService.java
│       │   │   │   ├── StaffRepository.java
│       │   │   │   └── Staff.java             ← Entity
│       │   │   │
│       │   │   ├── jobs/
│       │   │   │   ├── JobController.java
│       │   │   │   ├── JobService.java
│       │   │   │   ├── JobRepository.java
│       │   │   │   ├── JobQueueService.java
│       │   │   │   ├── JobNumberService.java
│       │   │   │   ├── Job.java               ← Entity
│       │   │   │   ├── JobType.java           ← Entity
│       │   │   │   └── JobActivity.java       ← Entity
│       │   │   │
│       │   │   ├── qa/
│       │   │   │   ├── QaController.java
│       │   │   │   ├── QaService.java
│       │   │   │   ├── QaRepository.java
│       │   │   │   ├── QaReview.java          ← Entity
│       │   │   │   └── DesignStandardService.java
│       │   │   │
│       │   │   ├── ai/
│       │   │   │   ├── AiAssistantService.java
│       │   │   │   ├── CopyGeneratorService.java
│       │   │   │   ├── ImageRankerService.java
│       │   │   │   ├── ColourPaletteService.java
│       │   │   │   ├── QaScannerService.java
│       │   │   │   └── ClaudeApiClient.java
│       │   │   │
│       │   │   ├── assets/
│       │   │   │   ├── AssetController.java
│       │   │   │   ├── AssetService.java
│       │   │   │   └── MinioService.java
│       │   │   │
│       │   │   ├── notifications/
│       │   │   │   ├── NotificationController.java
│       │   │   │   ├── NotificationService.java
│       │   │   │   ├── SseService.java
│       │   │   │   └── Notification.java      ← Entity
│       │   │   │
│       │   │   ├── sla/
│       │   │   │   ├── SlaMonitorService.java
│       │   │   │   └── SlaConfig.java
│       │   │   │
│       │   │   ├── performance/
│       │   │   │   ├── PerformanceController.java
│       │   │   │   └── PerformanceService.java
│       │   │   │
│       │   │   ├── events/
│       │   │   │   ├── PlatformEventListener.java
│       │   │   │   └── TenantActivatedEvent.java
│       │   │   │
│       │   │   └── dto/                       ← Request/Response DTOs
│       │   │       ├── request/
│       │   │       └── response/
│       │   │
│       │   └── resources/
│       │       ├── application.yml
│       │       ├── application-production.yml
│       │       └── db/migration/
│       │           ├── V1__create_staff.sql
│       │           ├── V2__create_job_types.sql
│       │           ├── V3__create_jobs.sql
│       │           ├── V4__create_job_activity.sql
│       │           ├── V5__create_qa_reviews.sql
│       │           ├── V6__create_notifications.sql
│       │           ├── V7__create_design_standards.sql
│       │           ├── V8__create_performance_cache.sql
│       │           └── V9__seed_job_types.sql
│       │
│       └── test/
│           └── java/io/conddo/studio/
│               ├── auth/AuthServiceTest.java
│               ├── jobs/JobServiceTest.java
│               ├── jobs/JobQueueServiceTest.java
│               ├── qa/QaServiceTest.java
│               ├── sla/SlaMonitorServiceTest.java
│               └── ai/AiAssistantServiceTest.java
│
└── frontend/                         ← Next.js application
    ├── package.json
    ├── tailwind.config.ts
    ├── next.config.js
    └── src/
        ├── app/
        │   ├── layout.tsx
        │   ├── page.tsx                    ← Redirects to /dashboard
        │   ├── login/
        │   │   └── page.tsx
        │   ├── dashboard/
        │   │   └── page.tsx                ← Staff home
        │   ├── jobs/
        │   │   ├── page.tsx                ← Available jobs
        │   │   ├── my-jobs/page.tsx
        │   │   └── [id]/
        │   │       ├── page.tsx            ← Job detail
        │   │       └── build/page.tsx      ← Studio builder
        │   ├── qa/
        │   │   ├── page.tsx                ← QA queue
        │   │   └── [id]/page.tsx           ← QA review
        │   ├── performance/
        │   │   └── page.tsx
        │   └── admin/
        │       ├── page.tsx                ← Operations dashboard
        │       ├── staff/page.tsx
        │       ├── job-types/page.tsx
        │       └── sla/page.tsx
        │
        ├── components/
        │   ├── layout/
        │   │   ├── Sidebar.tsx
        │   │   └── TopBar.tsx
        │   ├── jobs/
        │   │   ├── JobCard.tsx
        │   │   ├── JobDetail.tsx
        │   │   ├── JobBrief.tsx
        │   │   └── SlaBadge.tsx
        │   ├── builder/
        │   │   ├── StudioBuilder.tsx
        │   │   ├── SectionLibrary.tsx
        │   │   ├── SectionEditor.tsx
        │   │   ├── LivePreview.tsx
        │   │   ├── BrandPanel.tsx
        │   │   └── AiAssistantPanel.tsx
        │   ├── qa/
        │   │   ├── QaChecklist.tsx
        │   │   ├── RevisionDrawer.tsx
        │   │   └── StandardsReference.tsx
        │   ├── admin/
        │   │   ├── OperationsDashboard.tsx
        │   │   ├── StaffCapacity.tsx
        │   │   └── JobKanban.tsx
        │   └── ui/
        │       ├── StatusBadge.tsx
        │       ├── SlaCountdown.tsx
        │       └── JobTypePill.tsx
        │
        ├── lib/
        │   ├── api.ts                      ← API client
        │   ├── auth.ts                     ← Auth utilities
        │   ├── sse.ts                      ← SSE client
        │   └── utils.ts
        │
        ├── store/
        │   ├── authStore.ts               ← Zustand auth state
        │   ├── notificationStore.ts
        │   └── jobStore.ts
        │
        ├── hooks/
        │   ├── useJobs.ts
        │   ├── useSse.ts
        │   ├── usePermissions.ts
        │   └── useSla.ts
        │
        └── types/
            └── index.ts                   ← All TypeScript types
```

---

## 5. Database Schema

All tables live in the `studio` schema within the main `conddo` PostgreSQL database.

```sql
-- ─────────────────────────────────────────────────────────────
-- V1: STAFF
-- ─────────────────────────────────────────────────────────────

CREATE TABLE studio.staff (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email           TEXT UNIQUE NOT NULL,
    password_hash   TEXT NOT NULL,                    -- BCrypt cost 12
    full_name       TEXT NOT NULL,
    role            TEXT NOT NULL
                      CHECK (role IN (
                        'DEVELOPER',
                        'DESIGNER',
                        'WRITER',
                        'QA_REVIEWER',
                        'TEAM_LEAD',
                        'ADMIN'
                      )),
    skills          TEXT[] NOT NULL DEFAULT '{}',
    -- Values: WEBSITE_BUILD, GRAPHIC_DESIGN, CONTENT_WRITING,
    --         AD_CREATIVE, BRAND_KIT, WEBSITE_REVISION
    -- Staff only see jobs matching their skills
    is_active       BOOLEAN NOT NULL DEFAULT true,
    last_login_at   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Refresh tokens for staff (separate from platform tokens)
CREATE TABLE studio.staff_refresh_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    staff_id    UUID NOT NULL REFERENCES studio.staff(id) ON DELETE CASCADE,
    token_hash  TEXT UNIQUE NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ─────────────────────────────────────────────────────────────
-- V2: JOB TYPES
-- ─────────────────────────────────────────────────────────────

CREATE TABLE studio.job_types (
    id                  TEXT PRIMARY KEY,
    display_name        TEXT NOT NULL,
    description         TEXT,
    colour              TEXT NOT NULL DEFAULT '#7C5CBF',
    assigned_to_skills  TEXT[] NOT NULL,
    -- Skills required to see and claim this job type
    sla_hours           INTEGER NOT NULL,
    qa_required         BOOLEAN NOT NULL DEFAULT true,
    auto_create         BOOLEAN NOT NULL DEFAULT false,
    -- true = created automatically on platform events
    qa_checklist        JSONB NOT NULL DEFAULT '[]',
    -- Array of checklist items:
    -- [{id, label, required: bool, section: text}]
    is_active           BOOLEAN NOT NULL DEFAULT true,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ─────────────────────────────────────────────────────────────
-- V3: JOBS
-- ─────────────────────────────────────────────────────────────

CREATE TABLE studio.jobs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_number      TEXT UNIQUE NOT NULL,
    -- Format: WB-0001, GD-0001, CC-0001 etc.
    job_type_id     TEXT NOT NULL REFERENCES studio.job_types(id),
    tenant_id       UUID NOT NULL,
    -- References tenants table in public schema
    -- No FK constraint — avoids cross-schema FK issues
    -- Application layer ensures referential integrity

    title           TEXT NOT NULL,
    brief           JSONB NOT NULL DEFAULT '{}',
    -- Snapshot of business brief at time of job creation:
    -- {
    --   businessName, vertical, plan,
    --   description, services[], primaryColour,
    --   logoUrl, photoUrls[], openingHours,
    --   socialHandles, contactDetails,
    --   websiteRequirements: {template, sections[],
    --     colorPalette, ownerNotes}
    -- }

    assets          JSONB NOT NULL DEFAULT '[]',
    -- [{fileName, mimeType, sizeBytes, minioKey, uploadedAt}]

    status          TEXT NOT NULL DEFAULT 'QUEUED'
                      CHECK (status IN (
                        'QUEUED',
                        'AVAILABLE',
                        'ASSIGNED',
                        'IN_PROGRESS',
                        'SUBMITTED',
                        'IN_REVIEW',
                        'REVISION',
                        'APPROVED',
                        'DELIVERED',
                        'ESCALATED',
                        'CANCELLED'
                      )),

    assigned_to     UUID REFERENCES studio.staff(id),
    assigned_at     TIMESTAMPTZ,
    started_at      TIMESTAMPTZ,
    submitted_at    TIMESTAMPTZ,
    approved_at     TIMESTAMPTZ,
    delivered_at    TIMESTAMPTZ,

    sla_deadline    TIMESTAMPTZ NOT NULL,
    sla_extended_by INTEGER NOT NULL DEFAULT 0,
    -- Total hours of SLA extensions granted

    revision_count  INTEGER NOT NULL DEFAULT 0,
    -- How many times this job was returned for revision

    submission_notes TEXT,
    -- Developer's notes when submitting for QA

    ai_suggestions  JSONB NOT NULL DEFAULT '{}',
    -- AI-generated copy and suggestions stored here
    -- {heroHeadline, heroSub, servicesDescriptions[],
    --  aboutText, ctaText, seoTitle, seoDescription}

    studio_url      TEXT,
    -- Link to the website in Studio when built

    website_preview_url TEXT,
    -- Publicly accessible preview URL for QA review

    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Indexes
CREATE INDEX idx_studio_jobs_status
    ON studio.jobs (status, sla_deadline ASC);

CREATE INDEX idx_studio_jobs_assigned
    ON studio.jobs (assigned_to, status)
    WHERE assigned_to IS NOT NULL;

CREATE INDEX idx_studio_jobs_tenant
    ON studio.jobs (tenant_id);

CREATE INDEX idx_studio_jobs_type_status
    ON studio.jobs (job_type_id, status);

CREATE INDEX idx_studio_jobs_sla
    ON studio.jobs (sla_deadline)
    WHERE status IN ('ASSIGNED', 'IN_PROGRESS', 'SUBMITTED');

-- ─────────────────────────────────────────────────────────────
-- V4: JOB ACTIVITY LOG
-- ─────────────────────────────────────────────────────────────

CREATE TABLE studio.job_activity (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id      UUID NOT NULL REFERENCES studio.jobs(id) ON DELETE CASCADE,
    staff_id    UUID REFERENCES studio.staff(id),
    action      TEXT NOT NULL,
    -- JOB_CREATED, JOB_CLAIMED, JOB_STARTED, JOB_SUBMITTED,
    -- QA_STARTED, QA_APPROVED, QA_REVISION_SENT,
    -- JOB_ESCALATED, SLA_EXTENDED, JOB_DELIVERED
    detail      TEXT,
    -- Human-readable detail for the activity log
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_studio_job_activity_job
    ON studio.job_activity (job_id, created_at DESC);

-- ─────────────────────────────────────────────────────────────
-- V5: QA REVIEWS
-- ─────────────────────────────────────────────────────────────

CREATE TABLE studio.qa_reviews (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id          UUID NOT NULL REFERENCES studio.jobs(id),
    reviewer_id     UUID NOT NULL REFERENCES studio.staff(id),
    review_number   INTEGER NOT NULL DEFAULT 1,
    -- 1 = first review, 2 = after first revision, etc.
    outcome         TEXT NOT NULL
                      CHECK (outcome IN ('APPROVED', 'REVISION')),
    checklist       JSONB NOT NULL DEFAULT '{}',
    -- {checklistItemId: {passed: bool, note: text|null}}
    reviewer_notes  TEXT,
    -- Overall notes for the developer (shown on revision)
    positive_notes  TEXT,
    -- What was done well (encouragement — shown even on revision)
    ai_scan_result  JSONB,
    -- Stored AI QA scan output for this submission
    review_duration_minutes INTEGER,
    -- How long the review took
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_studio_qa_reviews_job
    ON studio.qa_reviews (job_id, created_at DESC);

-- ─────────────────────────────────────────────────────────────
-- V6: NOTIFICATIONS
-- ─────────────────────────────────────────────────────────────

CREATE TABLE studio.notifications (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    staff_id    UUID NOT NULL REFERENCES studio.staff(id),
    type        TEXT NOT NULL,
    -- NEW_JOB_AVAILABLE, JOB_ASSIGNED, REVISION_REQUESTED,
    -- JOB_APPROVED, SLA_WARNING, SLA_CRITICAL, JOB_ESCALATED
    title       TEXT NOT NULL,
    message     TEXT NOT NULL,
    job_id      UUID REFERENCES studio.jobs(id),
    is_read     BOOLEAN NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_studio_notifications_staff
    ON studio.notifications (staff_id, is_read, created_at DESC);

-- ─────────────────────────────────────────────────────────────
-- V7: DESIGN STANDARD LIBRARY
-- ─────────────────────────────────────────────────────────────

CREATE TABLE studio.design_standards (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_type_id TEXT NOT NULL REFERENCES studio.job_types(id),
    vertical    TEXT,
    -- null = applies to all verticals
    title       TEXT NOT NULL,
    description TEXT,
    asset_url   TEXT NOT NULL,
    -- MinIO presigned URL to the standard example
    thumbnail   TEXT,
    tags        TEXT[] NOT NULL DEFAULT '{}',
    is_active   BOOLEAN NOT NULL DEFAULT true,
    added_by    UUID REFERENCES studio.staff(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ─────────────────────────────────────────────────────────────
-- V8: PERFORMANCE CACHE
-- ─────────────────────────────────────────────────────────────

CREATE TABLE studio.staff_performance (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    staff_id                UUID NOT NULL REFERENCES studio.staff(id),
    period_month            DATE NOT NULL,
    -- First day of the month: 2026-05-01
    jobs_completed          INTEGER NOT NULL DEFAULT 0,
    jobs_target             INTEGER NOT NULL DEFAULT 15,
    first_pass_qa_rate      NUMERIC(5,2) NOT NULL DEFAULT 0.00,
    -- Percentage 0-100
    avg_build_minutes       INTEGER NOT NULL DEFAULT 0,
    sla_compliance_rate     NUMERIC(5,2) NOT NULL DEFAULT 100.00,
    revision_count          INTEGER NOT NULL DEFAULT 0,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (staff_id, period_month)
);

-- ─────────────────────────────────────────────────────────────
-- V9: SEED JOB TYPES
-- ─────────────────────────────────────────────────────────────

INSERT INTO studio.job_types
    (id, display_name, colour, assigned_to_skills,
     sla_hours, qa_required, auto_create, qa_checklist)
VALUES
(
    'WEBSITE_BUILD',
    'Website Build',
    '#7C5CBF',
    '{WEBSITE_BUILD}',
    48,
    true,
    true,
    '[
        {"id":"no_placeholder","label":"No placeholder text anywhere on the page","required":true,"section":"Content"},
        {"id":"all_sections","label":"All required sections present and populated","required":true,"section":"Content"},
        {"id":"copy_natural","label":"Copy reads naturally — not like a form was filled","required":true,"section":"Content"},
        {"id":"contact_accurate","label":"Contact details match the business brief exactly","required":true,"section":"Content"},
        {"id":"logo_placed","label":"Logo correctly placed and sized — not stretched","required":true,"section":"Visual"},
        {"id":"brand_colours","label":"Brand colours applied consistently throughout","required":true,"section":"Visual"},
        {"id":"images_quality","label":"All images are high quality — no blur or pixelation","required":true,"section":"Visual"},
        {"id":"typography","label":"Typography is consistent throughout","required":true,"section":"Visual"},
        {"id":"mobile_view","label":"Mobile view correct at 375px","required":true,"section":"Technical"},
        {"id":"tablet_view","label":"Tablet view correct at 768px","required":true,"section":"Technical"},
        {"id":"cta_buttons","label":"CTA buttons present and clearly labelled","required":true,"section":"Technical"},
        {"id":"design_standard","label":"Meets the Conddo.io design standard library","required":true,"section":"Standard"}
    ]'
),
(
    'WEBSITE_REVISION',
    'Website Revision',
    '#A07FD4',
    '{WEBSITE_BUILD}',
    24,
    true,
    false,
    '[
        {"id":"changes_applied","label":"All requested changes applied correctly","required":true,"section":"Content"},
        {"id":"no_regressions","label":"No regressions — previously approved sections unchanged","required":true,"section":"Technical"},
        {"id":"mobile_check","label":"Mobile view rechecked after changes","required":true,"section":"Technical"}
    ]'
),
(
    'GRAPHIC_DESIGN',
    'Graphic Design',
    '#F59E0B',
    '{GRAPHIC_DESIGN}',
    24,
    true,
    false,
    '[
        {"id":"brand_applied","label":"Brand colours and logo applied correctly","required":true,"section":"Visual"},
        {"id":"correct_dimensions","label":"Correct dimensions for intended platform","required":true,"section":"Technical"},
        {"id":"text_legible","label":"All text is legible at intended display size","required":true,"section":"Visual"},
        {"id":"file_format","label":"Exported in correct file format and resolution","required":true,"section":"Technical"}
    ]'
),
(
    'AD_CREATIVE',
    'Ad Creative',
    '#EF4444',
    '{GRAPHIC_DESIGN}',
    12,
    true,
    false,
    '[
        {"id":"ad_specs","label":"Meets Meta ad specifications (1080x1080 or 1080x1920)","required":true,"section":"Technical"},
        {"id":"no_text_over_20","label":"Text covers less than 20% of image area","required":true,"section":"Technical"},
        {"id":"brand_clear","label":"Brand is clearly identifiable","required":true,"section":"Visual"},
        {"id":"cta_visible","label":"Call to action is visible and compelling","required":true,"section":"Content"}
    ]'
),
(
    'BRAND_KIT',
    'Brand Kit',
    '#22C55E',
    '{GRAPHIC_DESIGN}',
    72,
    true,
    false,
    '[
        {"id":"logo_variations","label":"Logo provided in all required variations","required":true,"section":"Deliverables"},
        {"id":"colour_codes","label":"All colour codes documented (HEX, RGB)","required":true,"section":"Deliverables"},
        {"id":"typography_specified","label":"Typography clearly specified with weights","required":true,"section":"Deliverables"},
        {"id":"usage_guidelines","label":"Basic usage guidelines included","required":false,"section":"Deliverables"}
    ]'
),
(
    'CONTENT_WRITING',
    'Content Writing',
    '#3B82F6',
    '{CONTENT_WRITING}',
    24,
    true,
    false,
    '[
        {"id":"tone_correct","label":"Tone matches the business vertical and brand","required":true,"section":"Content"},
        {"id":"no_ai_slurp","label":"No AI slurp language (seamless, robust, leverage etc.)","required":true,"section":"Content"},
        {"id":"factually_accurate","label":"All facts match the business brief","required":true,"section":"Content"},
        {"id":"word_count","label":"Meets specified word count","required":true,"section":"Content"}
    ]'
);

-- Job number sequences — one per job type prefix
CREATE SEQUENCE studio.seq_wb START 1000;  -- Website Build
CREATE SEQUENCE studio.seq_wr START 1000;  -- Website Revision
CREATE SEQUENCE studio.seq_gd START 1000;  -- Graphic Design
CREATE SEQUENCE studio.seq_ac START 1000;  -- Ad Creative
CREATE SEQUENCE studio.seq_bk START 1000;  -- Brand Kit
CREATE SEQUENCE studio.seq_cw START 1000;  -- Content Writing
```

---

## 6. Authentication

### Staff Auth (Separate from Platform Auth)

Staff log in at `studio.conddo.io/login`. They receive a short-lived access token and a refresh token. This auth system is completely independent from the tenant JWT system used by business owners.

```java
// io/conddo/studio/auth/AuthService.java

@Service
public class AuthService {

    private final StaffRepository staffRepo;
    private final JwtService jwtService;
    private final RefreshTokenRepository tokenRepo;
    private final PasswordEncoder passwordEncoder;

    public AuthResponse login(LoginRequest req) {

        Staff staff = staffRepo.findByEmail(req.getEmail())
            .orElseThrow(() ->
                new BadCredentialsException("Invalid credentials"));

        if (!staff.isActive()) {
            throw new AccountDisabledException("Account suspended");
        }

        if (!passwordEncoder.matches(
                req.getPassword(), staff.getPasswordHash())) {
            throw new BadCredentialsException("Invalid credentials");
        }

        staff.setLastLoginAt(LocalDateTime.now());
        staffRepo.save(staff);

        String accessToken = jwtService.generateAccessToken(staff);
        String refreshToken = jwtService.generateRefreshToken(staff);

        return AuthResponse.builder()
            .accessToken(accessToken)
            .staff(StaffDto.from(staff))
            .build();
        // Refresh token set in httpOnly cookie by controller
    }
}
```

### JWT Payload for Staff

```json
{
  "sub": "staff-uuid",
  "email": "james@conddo.io",
  "name": "James Okafor",
  "role": "DEVELOPER",
  "skills": ["WEBSITE_BUILD"],
  "iat": 1716456000,
  "exp": 1716484800
}
```

### Access Control

```java
// Applied via @PreAuthorize on service methods

@PreAuthorize("hasRole('DEVELOPER') or hasRole('DESIGNER') or hasRole('WRITER')")
public Job claimJob(UUID jobId) { ... }

@PreAuthorize("hasRole('QA_REVIEWER') or hasRole('TEAM_LEAD')")
public QaReview startReview(UUID jobId) { ... }

@PreAuthorize("hasRole('TEAM_LEAD') or hasRole('ADMIN')")
public void reassignJob(UUID jobId, UUID staffId) { ... }

@PreAuthorize("hasRole('ADMIN')")
public Staff createStaff(CreateStaffRequest req) { ... }
```

---

## 7. Job System

### JobQueueService — Core Logic

```java
@Service
@Slf4j
public class JobQueueService {

    private final JobRepository jobRepo;
    private final StaffRepository staffRepo;
    private final NotificationService notifications;
    private final SseService sse;
    private final JobActivityService activity;
    private final JobNumberService numberService;

    // ── CREATE ─────────────────────────────────────────────

    // Called by PlatformEventListener when TenantActivated
    @Transactional
    public Job createWebsiteBuildJob(
            UUID tenantId,
            JobBrief brief) {

        JobType jobType = jobTypeRepo
            .findById("WEBSITE_BUILD")
            .orElseThrow();

        LocalDateTime slaDeadline = LocalDateTime.now()
            .plusHours(jobType.getSlaHours());

        Job job = new Job();
        job.setJobNumber(numberService.next("WB"));
        job.setJobTypeId("WEBSITE_BUILD");
        job.setTenantId(tenantId);
        job.setTitle("Website Build — " +
            brief.getBusinessName());
        job.setBrief(brief.toJsonb());
        job.setStatus(JobStatus.AVAILABLE);
        job.setSlaDeadline(slaDeadline);

        job = jobRepo.save(job);

        activity.log(job.getId(), null,
            "JOB_CREATED",
            "Auto-created from business signup");

        // Broadcast to all developers via SSE
        sse.broadcastToSkill(
            "WEBSITE_BUILD",
            SseEvent.newJob(job)
        );

        log.info("Created job {} for tenant {}",
            job.getJobNumber(), tenantId);

        return job;
    }

    // ── CLAIM ──────────────────────────────────────────────

    @Transactional
    public Job claimJob(UUID jobId, UUID staffId) {

        Job job = getJobOrThrow(jobId);

        if (job.getStatus() != JobStatus.AVAILABLE) {
            throw new JobNotClaimableException(
                job.getJobNumber() + " is not available");
        }

        Staff staff = getStaffOrThrow(staffId);

        // Check staff has required skill
        if (!staff.getSkills().contains(
                job.getJobTypeId())) {
            throw new InsufficientSkillException(
                "You cannot claim this job type");
        }

        job.setStatus(JobStatus.ASSIGNED);
        job.setAssignedTo(staffId);
        job.setAssignedAt(LocalDateTime.now());
        job = jobRepo.save(job);

        activity.log(jobId, staffId,
            "JOB_CLAIMED", null);

        return job;
    }

    // ── SUBMIT ─────────────────────────────────────────────

    @Transactional
    public Job submitForQa(
            UUID jobId,
            UUID staffId,
            String submissionNotes) {

        Job job = getJobOrThrow(jobId);

        validateJobOwnership(job, staffId);

        job.setStatus(JobStatus.SUBMITTED);
        job.setSubmittedAt(LocalDateTime.now());
        job.setSubmissionNotes(submissionNotes);
        job = jobRepo.save(job);

        activity.log(jobId, staffId,
            "JOB_SUBMITTED",
            "Submitted for QA review");

        // Notify all QA reviewers
        notifications.notifyRole(
            StaffRole.QA_REVIEWER,
            NotificationType.JOB_SUBMITTED,
            job.getJobNumber() + " ready for review",
            jobId
        );

        sse.broadcastToRole(
            StaffRole.QA_REVIEWER,
            SseEvent.jobSubmitted(job)
        );

        return job;
    }
}
```

### Job Number Generation

```java
@Service
public class JobNumberService {

    private final Map<String, String> prefixToSequence = Map.of(
        "WB", "seq_wb",
        "WR", "seq_wr",
        "GD", "seq_gd",
        "AC", "seq_ac",
        "BK", "seq_bk",
        "CW", "seq_cw"
    );

    @PersistenceContext
    private EntityManager em;

    public String next(String prefix) {
        String sequence = prefixToSequence.get(prefix);
        Long nextVal = (Long) em
            .createNativeQuery(
                "SELECT nextval('studio." + sequence + "')")
            .getSingleResult();
        return prefix + "-" + String.format("%04d", nextVal);
        // WB-1001, WB-1002, etc.
    }
}
```

---

## 8. AI Assistant Layer

### System Prompt Architecture

```java
@Service
@Slf4j
public class AiAssistantService {

    @Value("${studio.ai.claude.api-key}")
    private String apiKey;

    @Value("${studio.ai.claude.model}")
    private String model;

    // Layer 1: Always present
    private static final String SYSTEM_IDENTITY = """
        You are the AI assistant inside Conddo Studio, the internal
        website build tool for Conddo.io. Conddo.io builds professional
        websites for Nigerian SMEs. Every output must meet the
        Conddo.io design and copy standard.
        """;

    // Layer 2: Always present
    private static final String COPY_RULES = """
        Write in plain, direct English.
        Never use: seamless, robust, leverage, scalable solutions,
        cutting-edge, empower, revolutionize, transform, innovative,
        state-of-the-art, best-in-class.
        Short sentences. Active voice. Specific details.
        Lead with the customer's outcome, not the business description.
        """;

    // Layer 3: Injected per vertical from config
    private static final Map<String, String> VERTICAL_TONES = Map.of(
        "pharmacy",
        "Tone: Trustworthy, clinical but approachable. " +
        "Patients need to trust this pharmacy immediately. " +
        "Emphasise: genuine medications, licensed pharmacist, " +
        "community service, convenience.",

        "fashion",
        "Tone: Warm, personal, aspirational. " +
        "This is a craft business with individual attention. " +
        "Emphasise: custom made, made for you, attention to detail.",

        "logistics",
        "Tone: Efficient, direct, reliable. " +
        "Speed and reliability are the selling points. " +
        "Emphasise: on-time, tracked, dependable.",

        "professional_services",
        "Tone: Authoritative, credible, results-focused. " +
        "The client needs to trust the expertise. " +
        "Emphasise: experience, results, professionalism."
    );

    // ── COPY GENERATOR ──────────────────────────────────

    public CopyResult generateSectionCopy(
            JobBrief brief,
            String sectionType) {

        String toneGuide = VERTICAL_TONES
            .getOrDefault(brief.getVertical(),
                "Tone: Professional and approachable.");

        String systemPrompt =
            SYSTEM_IDENTITY + "\n\n" +
            COPY_RULES + "\n\n" +
            toneGuide + "\n\n" +
            getSectionInstructions(sectionType);

        String userPrompt = buildCopyPrompt(brief, sectionType);

        ClaudeResponse response = callClaude(
            systemPrompt, userPrompt, 800);

        return parseCopyResult(response);
    }

    private String getSectionInstructions(String sectionType) {
        return switch (sectionType) {
            case "HERO" -> """
                Hero section instructions:
                - Headline: Lead with the customer's outcome, not the
                  business description. One strong statement.
                - Subheadline: Adds specific context (location, what
                  makes them different). Max 15 words.
                - CTA: Specific action verb. 'Book a consultation'
                  not 'Contact us'.
                - Return JSON: {headline, subheadline, ctaText}
                """;
            case "SERVICES" -> """
                Services section instructions:
                - Write a 2-sentence description per service.
                - First sentence: what it is. Second: the benefit.
                - Use the services list from the brief.
                - Return JSON: {services: [{name, description}]}
                """;
            case "ABOUT" -> """
                About section instructions:
                - Write from the business description in the brief.
                - 3 sentences maximum.
                - Third sentence: why they should choose this business.
                - Return JSON: {aboutText}
                """;
            default -> "Return JSON with the relevant copy fields.";
        };
    }

    // ── IMAGE RANKER ─────────────────────────────────────

    public List<RankedImage> rankImages(
            List<String> imageUrls,
            String vertical,
            String sectionType) {

        List<RankedImage> results = new ArrayList<>();

        for (String url : imageUrls) {
            String prompt = String.format("""
                Rate this image for use on a %s business website
                in the %s section.
                
                Score 1-10 where:
                10 = perfect, professional, clearly shows the business
                5  = acceptable but not ideal
                1  = blurry, irrelevant, or poor quality
                
                Return JSON only:
                {"score": number, "reason": string,
                 "recommendation": "RECOMMENDED|ACCEPTABLE|REJECT"}
                """, vertical, sectionType);

            ClaudeResponse response = callClaudeWithImage(
                SYSTEM_IDENTITY, prompt, url, 200);

            results.add(parseImageRanking(url, response));
        }

        results.sort(Comparator
            .comparing(RankedImage::getScore).reversed());

        return results;
    }

    // ── QA SCANNER ───────────────────────────────────────

    public QaScanResult scanSubmission(
            Job job,
            String websiteHtmlSnapshot) {

        String prompt = String.format("""
            Review this Conddo.io website submission for %s (%s vertical).
            
            Business brief summary:
            %s
            
            Website content:
            %s
            
            Identify:
            1. ISSUES (must fix): anything that would fail QA
            2. SUGGESTIONS (optional improvements): things that could be better
            3. POSITIVES: what was done well
            
            Return JSON:
            {
              "issues": [{"section": string, "description": string}],
              "suggestions": [{"section": string, "description": string,
                "current": string, "suggested": string}],
              "positives": [string],
              "overallQuality": "PASS|BORDERLINE|FAIL"
            }
            """,
            job.getBrief().getBusinessName(),
            job.getBrief().getVertical(),
            job.getBrief().toSummaryText(),
            truncate(websiteHtmlSnapshot, 4000)
        );

        ClaudeResponse response = callClaude(
            SYSTEM_IDENTITY + "\n\n" + COPY_RULES,
            prompt, 2000);

        return parseQaScanResult(response);
    }

    // ── COLOUR PALETTE GENERATOR ─────────────────────────

    public ColourPalette generatePalette(String primaryHex) {

        String prompt = String.format("""
            Generate a complete, accessible colour palette for a
            professional website using %s as the primary colour.
            
            Requirements:
            - All text/background combinations must pass WCAG AA
              (minimum 4.5:1 contrast ratio for normal text)
            - Background should be clean white or near-white
            - The palette should feel professional and trustworthy
            
            Return JSON only:
            {
              "primary": string,
              "primaryHover": string,
              "primaryLight": string,
              "primaryBg": string,
              "background": string,
              "surface": string,
              "textPrimary": string,
              "textSecondary": string,
              "border": string
            }
            """, primaryHex);

        ClaudeResponse response = callClaude(
            SYSTEM_IDENTITY, prompt, 400);

        return parseColourPalette(response);
    }

    // ── HTTP CLIENT ──────────────────────────────────────

    private ClaudeResponse callClaude(
            String systemPrompt,
            String userPrompt,
            int maxTokens) {

        // POST to https://api.anthropic.com/v1/messages
        // Headers: x-api-key, anthropic-version, content-type
        // Body: {model, max_tokens, system, messages:[{role:user, content}]}
        // Return: data.content[0].text
        // Handle 429 (rate limit) with exponential backoff
        // Handle 500+ with retry up to 3 times
        // Timeout: 30 seconds
    }
}
```

---

## 9. Asset Management

```java
@Service
public class AssetService {

    private final MinioClient minioClient;

    @Value("${studio.minio.bucket}")
    private String bucket;

    // Upload asset for a job
    public AssetUploadResult upload(
            UUID jobId,
            MultipartFile file) {

        String sanitizedName = sanitizeFileName(
            file.getOriginalFilename());
        String key = String.format("jobs/%s/assets/%s/%s",
            jobId, UUID.randomUUID(), sanitizedName);

        minioClient.putObject(
            PutObjectArgs.builder()
                .bucket(bucket)
                .object(key)
                .stream(file.getInputStream(),
                    file.getSize(), -1)
                .contentType(file.getContentType())
                .build()
        );

        // Return presigned URL valid for 7 days
        String presignedUrl = minioClient.getPresignedObjectUrl(
            GetPresignedObjectUrlArgs.builder()
                .bucket(bucket)
                .object(key)
                .method(Method.GET)
                .expiry(7, TimeUnit.DAYS)
                .build()
        );

        return AssetUploadResult.builder()
            .key(key)
            .fileName(sanitizedName)
            .mimeType(file.getContentType())
            .sizeBytes(file.getSize())
            .presignedUrl(presignedUrl)
            .build();
    }

    // Refresh presigned URL (called when URL expires)
    public String refreshPresignedUrl(String key) {
        return minioClient.getPresignedObjectUrl(
            GetPresignedObjectUrlArgs.builder()
                .bucket(bucket)
                .object(key)
                .method(Method.GET)
                .expiry(7, TimeUnit.DAYS)
                .build()
        );
    }
}
```

---

## 10. QA System

```java
@Service
@Transactional
public class QaService {

    private final JobRepository jobRepo;
    private final QaRepository qaRepo;
    private final NotificationService notifications;
    private final AiAssistantService ai;
    private final SseService sse;

    public QaReview startReview(UUID jobId, UUID reviewerId) {

        Job job = jobRepo.findById(jobId)
            .orElseThrow(() -> new JobNotFoundException(jobId));

        if (job.getStatus() != JobStatus.SUBMITTED) {
            throw new InvalidJobStateException(
                "Job is not in SUBMITTED state");
        }

        job.setStatus(JobStatus.IN_REVIEW);
        jobRepo.save(job);

        QaReview review = new QaReview();
        review.setJobId(jobId);
        review.setReviewerId(reviewerId);
        review.setReviewNumber(
            qaRepo.countByJobId(jobId) + 1);

        return qaRepo.save(review);
    }

    public Job approveJob(
            UUID jobId,
            UUID reviewerId,
            QaApprovalRequest req) {

        Job job = jobRepo.findById(jobId).orElseThrow();

        // Save review record
        QaReview review = new QaReview();
        review.setJobId(jobId);
        review.setReviewerId(reviewerId);
        review.setOutcome(QaOutcome.APPROVED);
        review.setChecklist(req.getChecklist());
        review.setReviewerNotes(req.getReviewerNotes());
        review.setPositiveNotes(req.getPositiveNotes());
        qaRepo.save(review);

        // Update job status
        job.setStatus(JobStatus.APPROVED);
        job.setApprovedAt(LocalDateTime.now());
        jobRepo.save(job);

        activity.log(jobId, reviewerId,
            "QA_APPROVED", "Job approved by " +
            getStaffName(reviewerId));

        // Notify the platform — website is ready
        notifyPlatform(job);

        // Notify the developer
        notifications.notifyStaff(
            job.getAssignedTo(),
            NotificationType.JOB_APPROVED,
            job.getJobNumber() + " was approved!",
            jobId
        );

        return job;
    }

    public Job returnForRevision(
            UUID jobId,
            UUID reviewerId,
            RevisionRequest req) {

        Job job = jobRepo.findById(jobId).orElseThrow();

        // Save review record
        QaReview review = new QaReview();
        review.setJobId(jobId);
        review.setReviewerId(reviewerId);
        review.setOutcome(QaOutcome.REVISION);
        review.setChecklist(req.getChecklist());
        review.setReviewerNotes(req.getRevisionNotes());
        review.setPositiveNotes(req.getPositiveNotes());
        qaRepo.save(review);

        // Update job
        job.setStatus(JobStatus.REVISION);
        job.setRevisionCount(job.getRevisionCount() + 1);
        jobRepo.save(job);

        activity.log(jobId, reviewerId,
            "QA_REVISION_SENT",
            req.getRevisionNotes());

        // Notify developer
        notifications.notifyStaff(
            job.getAssignedTo(),
            NotificationType.REVISION_REQUESTED,
            job.getJobNumber() + " needs revision",
            jobId
        );

        // If second revision — also alert team lead
        if (job.getRevisionCount() >= 2) {
            notifications.notifyRole(
                StaffRole.TEAM_LEAD,
                NotificationType.JOB_SECOND_REVISION,
                job.getJobNumber() + " has had " +
                job.getRevisionCount() + " revisions",
                jobId
            );
        }

        return job;
    }
}
```

---

## 11. Notifications and Real-Time Updates

### SSE Service

```java
@Service
public class SseService {

    // Map of staffId → SseEmitter
    private final ConcurrentHashMap<UUID, SseEmitter>
        emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID staffId) {
        SseEmitter emitter = new SseEmitter(
            Long.MAX_VALUE); // Never timeout

        emitter.onCompletion(() ->
            emitters.remove(staffId));
        emitter.onTimeout(() ->
            emitters.remove(staffId));
        emitter.onError(e ->
            emitters.remove(staffId));

        emitters.put(staffId, emitter);

        // Send heartbeat every 30 seconds to keep alive
        scheduleHeartbeat(staffId, emitter);

        return emitter;
    }

    // Send event to a specific staff member
    public void send(UUID staffId, SseEvent event) {
        SseEmitter emitter = emitters.get(staffId);
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event()
                    .id(UUID.randomUUID().toString())
                    .name(event.getType())
                    .data(event.toJson())
                );
            } catch (IOException e) {
                emitters.remove(staffId);
            }
        }
    }

    // Broadcast to all staff with a specific skill
    public void broadcastToSkill(
            String skill, SseEvent event) {
        emitters.forEach((staffId, emitter) -> {
            Staff staff = staffRepo.findById(staffId)
                .orElse(null);
            if (staff != null &&
                    staff.getSkills().contains(skill)) {
                send(staffId, event);
            }
        });
    }

    // Broadcast to all staff with a specific role
    public void broadcastToRole(
            StaffRole role, SseEvent event) {
        emitters.forEach((staffId, emitter) -> {
            Staff staff = staffRepo.findById(staffId)
                .orElse(null);
            if (staff != null &&
                    staff.getRole() == role) {
                send(staffId, event);
            }
        });
    }
}
```

### SSE Event Types

```typescript
// frontend/src/types/index.ts

type SseEventType =
  | 'NEW_JOB_AVAILABLE'      // New job entered queue
  | 'JOB_CLAIMED'            // Someone else claimed a job
  | 'JOB_SUBMITTED'          // Job submitted for QA
  | 'JOB_APPROVED'           // QA approved
  | 'REVISION_REQUESTED'     // QA returned for revision
  | 'SLA_WARNING'            // Job entering amber zone
  | 'SLA_CRITICAL'           // Job entering red zone
  | 'JOB_ESCALATED'          // Job escalated to team lead
  | 'NOTIFICATION'           // Generic notification
  | 'HEARTBEAT'              // Keep-alive ping

// Frontend SSE client
// frontend/src/lib/sse.ts

export function connectSse(
  onEvent: (type: SseEventType, data: unknown) => void
) {
  const source = new EventSource(
    '/api/studio/events',
    { withCredentials: true }
  )

  const eventTypes: SseEventType[] = [
    'NEW_JOB_AVAILABLE', 'JOB_CLAIMED', 'JOB_SUBMITTED',
    'JOB_APPROVED', 'REVISION_REQUESTED', 'SLA_WARNING',
    'SLA_CRITICAL', 'JOB_ESCALATED', 'NOTIFICATION'
  ]

  eventTypes.forEach(type => {
    source.addEventListener(type, (e: MessageEvent) => {
      onEvent(type, JSON.parse(e.data))
    })
  })

  source.onerror = () => {
    // Reconnect after 5 seconds
    setTimeout(() => connectSse(onEvent), 5000)
  }

  return () => source.close()
}
```

---

## 12. SLA Engine

```java
@Service
@Slf4j
public class SlaMonitorService {

    @Value("${studio.sla.amber-threshold:24}")
    private int amberHours;

    @Value("${studio.sla.red-threshold:6}")
    private int redHours;

    @Value("${studio.sla.escalation-threshold:2}")
    private int escalationHours;

    // Runs every 10 minutes
    @Scheduled(cron = "0 */10 * * * *")
    public void monitorSla() {

        LocalDateTime now = LocalDateTime.now();

        checkEscalations(now);
        checkRedZone(now);
        checkAmberZone(now);
    }

    private void checkEscalations(LocalDateTime now) {

        List<Job> jobs = jobRepo
            .findActiveJobsDueBefore(
                now.plusHours(escalationHours));

        jobs.forEach(job -> {
            if (job.getStatus() == JobStatus.ESCALATED)
                return; // Already escalated

            job.setStatus(JobStatus.ESCALATED);
            jobRepo.save(job);

            activity.log(job.getId(), null,
                "JOB_ESCALATED",
                "Auto-escalated: SLA breach in " +
                escalationHours + " hours");

            sse.broadcastToRole(StaffRole.TEAM_LEAD,
                SseEvent.escalation(job));

            notifications.notifyRole(
                StaffRole.TEAM_LEAD,
                NotificationType.JOB_ESCALATED,
                "ESCALATION: " + job.getJobNumber() +
                " — SLA breach imminent",
                job.getId()
            );

            log.warn("Job {} escalated — SLA breach imminent",
                job.getJobNumber());
        });
    }

    private void checkRedZone(LocalDateTime now) {

        List<Job> jobs = jobRepo
            .findActiveJobsDueBetween(
                now.plusHours(escalationHours),
                now.plusHours(redHours));

        jobs.stream()
            .filter(j -> !j.isRedAlertSent())
            .forEach(job -> {

                notifications.notifyStaff(
                    job.getAssignedTo(),
                    NotificationType.SLA_CRITICAL,
                    "URGENT: " + job.getJobNumber() +
                    " is due in under " + redHours + " hours",
                    job.getId()
                );

                sse.broadcastToRole(StaffRole.TEAM_LEAD,
                    SseEvent.slaRisk(job, "RED"));

                job.setRedAlertSent(true);
                jobRepo.save(job);
            });
    }

    private void checkAmberZone(LocalDateTime now) {

        List<Job> jobs = jobRepo
            .findActiveJobsDueBetween(
                now.plusHours(redHours),
                now.plusHours(amberHours));

        jobs.stream()
            .filter(j -> !j.isAmberAlertSent())
            .forEach(job -> {

                notifications.notifyStaff(
                    job.getAssignedTo(),
                    NotificationType.SLA_WARNING,
                    job.getJobNumber() +
                    " is due in under " + amberHours +
                    " hours — keep going",
                    job.getId()
                );

                job.setAmberAlertSent(true);
                jobRepo.save(job);
            });
    }
}
```

---

## 13. Design Standard Library

```java
@Service
public class DesignStandardService {

    private final DesignStandardRepository repo;
    private final MinioService minio;

    // Get standards for a given job type and vertical
    public List<DesignStandard> getStandards(
            String jobTypeId,
            String vertical) {

        return repo.findByJobTypeIdAndVerticalOrNull(
            jobTypeId, vertical)
            .stream()
            .filter(DesignStandard::isActive)
            .map(standard -> {
                // Refresh presigned URL
                standard.setAssetUrl(
                    minio.refreshPresignedUrl(
                        standard.getMinioKey()));
                return standard;
            })
            .collect(toList());
    }

    // Team lead adds a new standard example
    public DesignStandard addStandard(
            AddStandardRequest req,
            UUID addedByStaffId) {

        String key = minio.upload(
            "standards/" + req.getJobTypeId() + "/",
            req.getFile()
        ).getKey();

        DesignStandard standard = new DesignStandard();
        standard.setJobTypeId(req.getJobTypeId());
        standard.setVertical(req.getVertical());
        standard.setTitle(req.getTitle());
        standard.setDescription(req.getDescription());
        standard.setMinioKey(key);
        standard.setTags(req.getTags());
        standard.setAddedBy(addedByStaffId);

        return repo.save(standard);
    }
}
```

---

## 14. Performance Tracking

```java
@Service
public class PerformanceService {

    // Recalculates performance for all staff monthly
    @Scheduled(cron = "0 0 1 * * *")
    // Runs at 1am daily
    public void recalculateCurrentMonth() {

        LocalDate monthStart = LocalDate.now()
            .withDayOfMonth(1);

        staffRepo.findAllActive().forEach(staff -> {
            recalculateForStaff(staff.getId(), monthStart);
        });
    }

    @Transactional
    public void recalculateForStaff(
            UUID staffId, LocalDate monthStart) {

        LocalDateTime start = monthStart.atStartOfDay();
        LocalDateTime end = monthStart.plusMonths(1)
            .atStartOfDay();

        List<Job> completed = jobRepo
            .findByAssignedToAndStatusAndDeliveredAtBetween(
                staffId,
                JobStatus.DELIVERED,
                start, end
            );

        int jobsCompleted = completed.size();

        // First-pass QA rate
        // = jobs approved on first review / total completed
        long firstPassCount = completed.stream()
            .filter(job -> {
                int reviewCount = qaRepo.countByJobId(job.getId());
                Optional<QaReview> firstReview =
                    qaRepo.findFirstByJobId(job.getId());
                return firstReview
                    .map(r -> r.getOutcome() == QaOutcome.APPROVED)
                    .orElse(false) && reviewCount == 1;
            })
            .count();

        double firstPassRate = jobsCompleted > 0
            ? (firstPassCount * 100.0) / jobsCompleted
            : 0.0;

        // Average build time
        OptionalDouble avgBuildMinutes = completed.stream()
            .filter(j -> j.getStartedAt() != null &&
                         j.getSubmittedAt() != null)
            .mapToLong(j -> Duration.between(
                j.getStartedAt(),
                j.getSubmittedAt()).toMinutes())
            .average();

        // SLA compliance
        long onTimeCount = completed.stream()
            .filter(j -> j.getDeliveredAt() != null &&
                !j.getDeliveredAt()
                    .isAfter(j.getSlaDeadline()))
            .count();

        double slaRate = jobsCompleted > 0
            ? (onTimeCount * 100.0) / jobsCompleted
            : 100.0;

        // Upsert performance record
        StaffPerformance perf = perfRepo
            .findByStaffIdAndPeriodMonth(staffId, monthStart)
            .orElse(new StaffPerformance());

        perf.setStaffId(staffId);
        perf.setPeriodMonth(monthStart);
        perf.setJobsCompleted(jobsCompleted);
        perf.setFirstPassQaRate(
            BigDecimal.valueOf(firstPassRate)
                .setScale(2, HALF_UP));
        perf.setAvgBuildMinutes(
            (int) avgBuildMinutes.orElse(0));
        perf.setSlaComplianceRate(
            BigDecimal.valueOf(slaRate)
                .setScale(2, HALF_UP));

        perfRepo.save(perf);
    }
}
```

---

## 15. REST API Reference

All endpoints are prefixed with `/api/studio`.
Authentication: `Authorization: Bearer <staff_access_token>`

```
AUTH
  POST   /auth/login              Login with email + password
  POST   /auth/refresh            Refresh access token
  POST   /auth/logout             Revoke refresh token
  GET    /auth/me                 Current staff profile

EVENTS (SSE)
  GET    /events                  SSE stream for current staff

JOBS
  GET    /jobs                    List jobs (filter by status, type, skill)
  GET    /jobs/available          Jobs available for me to claim
  GET    /jobs/my-jobs            My active jobs
  GET    /jobs/:id                Job detail + full brief
  POST   /jobs/:id/claim          Claim an available job
  PATCH  /jobs/:id/start          Mark as In Progress
  PATCH  /jobs/:id/ai-suggest     Request AI copy suggestions
  PATCH  /jobs/:id/rank-images    Request AI image ranking
  POST   /jobs/:id/submit         Submit for QA
  GET    /jobs/:id/activity       Job activity log

QA
  GET    /qa/queue                All submitted jobs awaiting review
  POST   /qa/:id/start            Start reviewing a job
  POST   /qa/:id/approve          Approve job
  POST   /qa/:id/return           Return for revision
  GET    /qa/:id/scan             Run AI QA scan on submitted job

ASSETS
  POST   /assets/upload/:jobId    Upload asset for a job
  GET    /assets/:key/refresh     Refresh presigned URL

NOTIFICATIONS
  GET    /notifications           My notifications (unread first)
  PATCH  /notifications/:id/read  Mark as read
  PATCH  /notifications/read-all  Mark all as read

PERFORMANCE
  GET    /performance/me          My performance stats
  GET    /performance/me/history  My job history

DESIGN STANDARDS
  GET    /standards               Standards (filter by job type, vertical)

ADMIN (TEAM_LEAD and ADMIN only)
  GET    /admin/dashboard         Operations overview
  GET    /admin/jobs              All jobs with full filters
  PATCH  /admin/jobs/:id/reassign Reassign job to different staff
  PATCH  /admin/jobs/:id/escalate Manual escalation
  PATCH  /admin/jobs/:id/extend   Extend SLA (with reason)
  PATCH  /admin/jobs/:id/cancel   Cancel job

  GET    /admin/staff             All staff with performance
  POST   /admin/staff             Create new staff member
  GET    /admin/staff/:id         Staff detail + performance
  PATCH  /admin/staff/:id         Update staff (role, skills)
  PATCH  /admin/staff/:id/suspend Suspend staff access

  GET    /admin/job-types         All job types
  POST   /admin/job-types         Create new job type
  PATCH  /admin/job-types/:id     Update job type

  POST   /admin/standards         Add to design standard library
  DELETE /admin/standards/:id     Remove from library

  GET    /admin/sla               SLA health overview
  PATCH  /admin/sla/settings      Update SLA thresholds

BUILDER (see §21 for full spec)
  GET    /jobs/:id/site                       Get the job's site (pages + sections + theme)
  PUT    /jobs/:id/site                       Replace the entire site (for import / bulk save)
  PATCH  /jobs/:id/site/theme                 Update theme (primary hex, fonts)
  POST   /jobs/:id/site/pages                 Create a page
  PATCH  /jobs/:id/site/pages/:pageId         Update page (title, slug, order)
  DELETE /jobs/:id/site/pages/:pageId         Delete page
  POST   /jobs/:id/site/pages/:pageId/sections             Add a section to a page
  PATCH  /jobs/:id/site/pages/:pageId/sections/:sectionId  Update section content / order
  DELETE /jobs/:id/site/pages/:pageId/sections/:sectionId  Remove a section
  POST   /jobs/:id/site/publish                Mark current site state as the submitted build

EXPORT / IMPORT (see §22 for full spec)
  GET    /jobs/:id/export                      Download job as a ZIP bundle (manifest + assets + site)
  POST   /jobs/:id/import                      Upload a previously-exported bundle to replace state
```

---

## 16. Frontend Structure

### Role-Based Layout

```typescript
// frontend/src/app/layout.tsx

// The sidebar nav items differ by role:

const NAV_BY_ROLE: Record<StaffRole, NavItem[]> = {
  DEVELOPER: [
    { label: 'Dashboard', href: '/dashboard', icon: 'home' },
    { label: 'Available Jobs', href: '/jobs', icon: 'briefcase',
      badge: availableCount },
    { label: 'My Jobs', href: '/jobs/my-jobs', icon: 'list' },
    { label: 'Performance', href: '/performance', icon: 'bar-chart' },
  ],
  DESIGNER: [
    { label: 'Dashboard', href: '/dashboard', icon: 'home' },
    { label: 'Available Jobs', href: '/jobs', icon: 'briefcase' },
    { label: 'My Jobs', href: '/jobs/my-jobs', icon: 'list' },
    { label: 'Performance', href: '/performance', icon: 'bar-chart' },
  ],
  QA_REVIEWER: [
    { label: 'QA Queue', href: '/qa', icon: 'check-circle',
      badge: qaQueueCount },
    { label: 'Completed Today', href: '/qa/history', icon: 'clock' },
    { label: 'Standards Library', href: '/standards', icon: 'book' },
  ],
  TEAM_LEAD: [
    { label: 'Operations', href: '/admin', icon: 'activity' },
    { label: 'All Jobs', href: '/admin/jobs', icon: 'briefcase' },
    { label: 'QA Queue', href: '/qa', icon: 'check-circle' },
    { label: 'Staff', href: '/admin/staff', icon: 'users' },
    { label: 'SLA', href: '/admin/sla', icon: 'alert-triangle' },
    { label: 'Job Types', href: '/admin/job-types', icon: 'settings' },
    { label: 'Standards', href: '/standards', icon: 'book' },
  ],
  ADMIN: [
    // Everything TEAM_LEAD sees plus:
    { label: 'Staff', href: '/admin/staff', icon: 'users' },
  ]
}
```

### Key Frontend Rules

```typescript
// All SLA countdowns must use Geist Mono
// Example:
<span className="font-mono text-sm">
  {formatSla(job.slaDeadline)}
</span>

// SLA colour logic
function getSlaColour(deadline: Date): string {
  const hoursLeft = differenceInHours(deadline, new Date())
  if (hoursLeft < 0) return 'text-red-500'     // Breached
  if (hoursLeft < 6) return 'text-red-400'     // Critical
  if (hoursLeft < 24) return 'text-amber-400'  // At risk
  return 'text-green-400'                       // Safe
}

// Job type pill colours
const JOB_TYPE_COLOURS: Record<string, string> = {
  WEBSITE_BUILD:    'bg-violet-500/15 text-violet-400 border-violet-500/30',
  WEBSITE_REVISION: 'bg-violet-300/15 text-violet-300 border-violet-300/30',
  GRAPHIC_DESIGN:   'bg-amber-500/15 text-amber-400 border-amber-500/30',
  AD_CREATIVE:      'bg-red-500/15 text-red-400 border-red-500/30',
  BRAND_KIT:        'bg-green-500/15 text-green-400 border-green-500/30',
  CONTENT_WRITING:  'bg-blue-500/15 text-blue-400 border-blue-500/30',
}

// Never use Lorem Ipsum in any component
// Always use realistic Nigerian business names in fixtures and tests
```

---

## 17. Environment Variables

```bash
# Backend — application.yml reads these

# Database
DATABASE_URL=jdbc:postgresql://postgres:5432/conddo
DB_PASSWORD=<password>

# Redis
REDIS_URL=redis://:password@redis:6379

# MinIO
MINIO_URL=http://minio:9000
MINIO_ROOT_USER=conddo-admin
MINIO_ROOT_PASSWORD=<password>
MINIO_BUCKET_STUDIO=conddo-studio

# Studio JWT (separate from platform JWT)
STUDIO_JWT_SECRET=<random-256-bit-secret>
STUDIO_JWT_EXPIRY=28800000

# AI
CLAUDE_API_KEY=<anthropic-api-key>
CLAUDE_MODEL=claude-sonnet-4-20250514

# SLA thresholds (hours)
STUDIO_SLA_AMBER_THRESHOLD=24
STUDIO_SLA_RED_THRESHOLD=6
STUDIO_SLA_ESCALATION_THRESHOLD=2

# Notifications
BREVO_API_KEY=<brevo-api-key>
BREVO_SENDER_EMAIL=studio@conddo.io

# Main platform (for cross-service calls)
MAIN_API_URL=http://conddo-gateway:8080
MAIN_API_INTERNAL_KEY=<shared-internal-key>

# Frontend
NEXT_PUBLIC_API_URL=https://studio.conddo.io/api/studio
NEXT_PUBLIC_SSE_URL=https://studio.conddo.io/api/studio/events
```

---

## 18. Docker Configuration

```dockerfile
# backend/Dockerfile

FROM eclipse-temurin:21-jre-alpine

RUN addgroup -g 1001 conddo && \
    adduser -u 1001 -G conddo -s /bin/sh -D conddo

WORKDIR /app
COPY target/*.jar app.jar
RUN chown conddo:conddo app.jar

USER conddo

ENV JAVA_OPTS="-XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:+UseG1GC \
  -XX:+ExitOnOutOfMemoryError"

EXPOSE 8083

HEALTHCHECK --interval=30s --timeout=10s \
  --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8083/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

```yaml
# In the main docker-compose.yml:

conddo-studio:
  build:
    context: ./conddo-studio/backend
    dockerfile: Dockerfile
  container_name: conddo-studio
  restart: unless-stopped
  ports:
    - "127.0.0.1:8083:8083"
  environment:
    SPRING_PROFILES_ACTIVE: production
    SERVER_PORT: 8083
    DATABASE_URL: ${DATABASE_URL}
    DB_PASSWORD: ${DB_PASSWORD}
    REDIS_URL: ${REDIS_URL}
    MINIO_URL: ${MINIO_URL}
    MINIO_ROOT_USER: ${MINIO_ROOT_USER}
    MINIO_ROOT_PASSWORD: ${MINIO_ROOT_PASSWORD}
    MINIO_BUCKET_STUDIO: ${MINIO_BUCKET_STUDIO}
    STUDIO_JWT_SECRET: ${STUDIO_JWT_SECRET}
    CLAUDE_API_KEY: ${CLAUDE_API_KEY}
    CLAUDE_MODEL: ${CLAUDE_MODEL}
    BREVO_API_KEY: ${BREVO_API_KEY}
    MAIN_API_URL: http://conddo-gateway:8080
    MAIN_API_INTERNAL_KEY: ${MAIN_API_INTERNAL_KEY}
  networks:
    - conddo-internal
  depends_on:
    postgres:
      condition: service_healthy
    redis:
      condition: service_healthy
    minio:
      condition: service_healthy
  deploy:
    resources:
      limits:
        memory: 1G
```

---

## 19. Implementation Sequence

Build in this exact order. Each step depends on the previous.

```
PHASE 1 — Foundation (Week 1)
  ✓ Maven project setup
  ✓ Spring Boot scaffold with Security config
  ✓ Database schema (all migrations V1–V9)
  ✓ Staff auth (login, JWT, refresh, logout)
  ✓ Basic health endpoint
  Definition of done: staff can log in and receive a JWT

PHASE 2 — Job System (Week 2)
  ✓ Job entity and repository
  ✓ JobQueueService (create, claim, start, submit)
  ✓ Job activity logging
  ✓ Job number generation
  ✓ REST endpoints: GET /jobs, POST /:id/claim, PATCH /:id/start
  Definition of done: staff can see and claim jobs

PHASE 3 — Assets and Brief (Week 3)
  ✓ MinIO integration
  ✓ Asset upload endpoint
  ✓ Job detail endpoint returning full brief + assets
  ✓ Platform event listener (TenantActivated → create job)
  Definition of done: jobs are created automatically from signups
                      with full business brief

PHASE 4 — QA System (Week 4)
  ✓ QA review entities
  ✓ QaService (start, approve, return)
  ✓ QA REST endpoints
  ✓ Second revision team lead alert
  Definition of done: QA reviewer can approve or return a job

PHASE 5 — AI Layer (Week 5)
  ✓ Claude API client
  ✓ Copy generator
  ✓ Image ranker
  ✓ Colour palette generator
  ✓ QA scanner
  Definition of done: AI suggestions appear in the job detail

PHASE 6 — Real-Time (Week 6)
  ✓ SSE service
  ✓ Notification service (Brevo email + in-app)
  ✓ SSE endpoint
  ✓ Notification endpoints
  Definition of done: staff receive real-time job updates

PHASE 7 — SLA Engine (Week 6, parallel with Phase 6)
  ✓ SlaMonitorService with @Scheduled
  ✓ Amber, red, escalation alerts
  ✓ Escalation broadcasts to team lead
  Definition of done: at-risk jobs alert team lead automatically

PHASE 8 — Admin and Performance (Week 7)
  ✓ Operations dashboard endpoint
  ✓ Staff management endpoints
  ✓ Job type configuration
  ✓ SLA settings
  ✓ PerformanceService with daily recalculation
  Definition of done: team lead can see and manage everything

PHASE 9 — Frontend (Weeks 8–11)
  ✓ Next.js setup with Tailwind dark mode
  ✓ Login and auth flow
  ✓ Dashboard (all roles)
  ✓ Available jobs + claim flow
  ✓ Job detail + brief panel
  ✓ Submit for QA
  ✓ QA queue + review screen
  ✓ Revision drawer
  ✓ Admin operations dashboard
  ✓ SSE client integration
  ✓ Notification panel
  Definition of done: all roles can complete their full workflow

PHASE 10 — Testing and Polish (Week 12)
  ✓ Unit tests for all service classes
  ✓ Integration tests for critical paths
  ✓ SLA monitoring end-to-end test
  ✓ Load test: 50 concurrent SSE connections
  ✓ Security: ensure no cross-staff data leakage
  Definition of done: all tests pass, ready for production

PHASE 11 — Website Builder (post-V1)   ← see §21
  ☐ V3__studio_builder.sql migration: sites, site_pages, site_sections
  ☐ Site / Page / Section JPA entities with optimistic locking
  ☐ SiteService: lazy-create on first GET; validate section content per type
  ☐ BuilderController endpoints (see §15 BUILDER group)
  ☐ Wire /jobs/:id/submit to auto-publish the site
  ☐ Add site.section_updated + site.published SSE events to SseService
  ☐ Section type catalogue (HERO/SERVICES/ABOUT/CTA/GALLERY/CONTACT/CUSTOM)
  ☐ AI assistant writes directly into matching section content (existing
    /ai-suggest endpoint now reads/writes site_sections instead of job.ai_suggestions)
  Definition of done: a developer can build a full multi-page website in
    Studio, see live changes via SSE, and submit it for QA without
    leaving the app

PHASE 12 — Job Export / Import (post-V1)   ← see §22
  ☐ GET /jobs/:id/export — streams a ZIP (manifest, brief.md, site.json,
    ai-suggestions.json, qa-history.json, activity.json, /assets/*)
  ☐ Server-side Cloudinary asset download (no redirect — bundle must
    work offline)
  ☐ SHA-256 checksum in manifest; verified on re-import
  ☐ POST /jobs/:id/import (multipart) — applies bundle atomically;
    optimistic-lock check via manifest.job.version vs current
  ☐ Activity log: JOB_EXPORTED, JOB_IMPORTED entries
  ☐ Env vars: STUDIO_EXPORT_ASSET_INLINE_MAX_BYTES (50 MB default),
    STUDIO_IMPORT_MAX_BYTES (256 MB default)
  Definition of done: a staff member can export a job, work on it
    locally (edit the brief, run AI prompts offline, drop new assets
    in /assets/), and re-import to overwrite cloud state — with a
    clear 409 if someone else changed the job meanwhile
```

---

## 21. Website Builder API

**Status:** Not yet implemented. Currently the developer builds the website in an
external Studio tool and submits its URL via `/jobs/:id/submit`. This section
specifies the in-app builder backend so that flow can move inside Conddo Studio.

### 21.1 Domain Model

A **Site** is 1:1 with a Job — the customer's website-in-progress. Sites hold
**Pages** (home, services, about, contact, …). Pages hold ordered **Sections**
(HERO, SERVICES, ABOUT, CTA, GALLERY, CONTACT, …). Sections are typed: each
type has a known content schema (validated server-side) plus a freeform JSONB
overrides bucket for one-off tweaks.

```
Site
  ├── theme: { primaryHex, fontHeading, fontBody }
  ├── meta: { title, description, favicon? }
  ├── pages: Page[]
  │     ├── id, slug, title, order, isHome
  │     └── sections: Section[]
  │           ├── id, type (HERO|SERVICES|ABOUT|...), order
  │           └── content: JSONB (shape determined by type)
  └── status: DRAFT | PUBLISHED
```

### 21.2 Database Schema (Flyway: V3__studio_builder.sql)

```sql
CREATE TABLE studio.sites (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id          UUID NOT NULL UNIQUE REFERENCES studio.jobs(id) ON DELETE CASCADE,
    theme           JSONB NOT NULL DEFAULT '{}'::jsonb,
    meta            JSONB NOT NULL DEFAULT '{}'::jsonb,
    status          TEXT NOT NULL DEFAULT 'DRAFT'
                      CHECK (status IN ('DRAFT','PUBLISHED')),
    published_at    TIMESTAMPTZ,
    version         INTEGER NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE studio.site_pages (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    site_id       UUID NOT NULL REFERENCES studio.sites(id) ON DELETE CASCADE,
    slug          TEXT NOT NULL,
    title         TEXT NOT NULL,
    is_home       BOOLEAN NOT NULL DEFAULT false,
    order_index   INTEGER NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (site_id, slug)
);

CREATE TABLE studio.site_sections (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    page_id       UUID NOT NULL REFERENCES studio.site_pages(id) ON DELETE CASCADE,
    section_type  TEXT NOT NULL,                 -- HERO | SERVICES | ABOUT | CTA | GALLERY | CONTACT | CUSTOM
    content       JSONB NOT NULL DEFAULT '{}'::jsonb,
    order_index   INTEGER NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_site_pages_site ON studio.site_pages (site_id, order_index);
CREATE INDEX idx_site_sections_page ON studio.site_sections (page_id, order_index);
```

A site is created lazily on first builder open if one doesn't exist for the job.
On `POST /jobs/:id/submit`, the site is auto-`PUBLISHED` and its rendered URL
(once `conddo-sites` is online — see §11) replaces the manual `studioUrl` field.

### 21.3 Endpoint Contracts

All endpoints require the staff member to be assigned to the job (`assignedTo`
matches the caller), or be `TEAM_LEAD` / `ADMIN`. Optimistic locking via the
`Site.version` field — every mutation increments it; clients must send the
expected version in `If-Match` or get `409 Conflict`.

```
GET    /api/jobs/:id/site
       → 200 { site: { id, theme, meta, status, version, pages: [{ id, slug,
                       title, order, isHome, sections: [...] }] } }
       → 404 if no site yet (FE should call PUT to lazily create)

PUT    /api/jobs/:id/site
       Body: complete Site object (theme + meta + pages + sections).
       Replaces the entire site state — used by import and by "save all"
       in the builder when the user has done many local edits.
       Headers: If-Match: <version>
       → 200 { site }     (version incremented)
       → 409 if If-Match mismatches

PATCH  /api/jobs/:id/site/theme
       Body: { primaryHex?, fontHeading?, fontBody? }
       → 200 { site.theme }

POST   /api/jobs/:id/site/pages
       Body: { slug, title, isHome?, order? }
       → 201 { page }

PATCH  /api/jobs/:id/site/pages/:pageId
       Body: { title?, slug?, order?, isHome? }
       → 200 { page }

DELETE /api/jobs/:id/site/pages/:pageId
       → 204 (cannot delete the home page; returns 422 with code HOME_PAGE_REQUIRED)

POST   /api/jobs/:id/site/pages/:pageId/sections
       Body: { type, content?, order? }
       → 201 { section }

PATCH  /api/jobs/:id/site/pages/:pageId/sections/:sectionId
       Body: { content?, order? }     (type is immutable; delete + create to change)
       → 200 { section }

DELETE /api/jobs/:id/site/pages/:pageId/sections/:sectionId
       → 204

POST   /api/jobs/:id/site/publish
       Marks the site PUBLISHED, sets published_at, snapshots into the
       activity log as `SITE_PUBLISHED`. Auto-fired by `/jobs/:id/submit`.
       → 200 { site }
```

### 21.4 Section Type Catalogue

The backend validates each section's `content` against the type. Initial set:

| Type     | Content shape (JSONB) |
|----------|----------------------|
| `HERO`     | `{ headline, subheadline, ctaText, ctaHref, backgroundImage? }` |
| `SERVICES` | `{ heading, services: [{ name, description, icon? }] }` |
| `ABOUT`    | `{ heading, body, image? }` |
| `CTA`      | `{ headline, body?, primaryCta: { text, href }, secondaryCta? }` |
| `GALLERY`  | `{ heading, images: [{ url, alt, caption? }] }` |
| `CONTACT`  | `{ heading, address?, phone?, email?, mapUrl?, hours? }` |
| `CUSTOM`   | `{ html, css? }` — escape hatch; never validated beyond size limit |

AI suggestions (existing `/jobs/:id/ai-suggest` endpoint) write directly into a
matching section's `content` when one exists.

### 21.5 SSE Event Additions

Add to `SseService` broadcast set:

```
site.section_updated   { jobId, pageId, sectionId, version }
site.published          { jobId, version, publishedAt }
```

Both filtered to: the assignee, all TEAM_LEAD/ADMIN, and (for cross-staff
collaboration later) any other staff currently subscribed to the same job.

---

## 22. Job Export & Import

**Status:** Not yet implemented. Required so staff can take a job off the
cloud, work on it on their local machine (offline or in their preferred local
tools), and re-import their changes when they're back online.

### 22.1 The Bundle (ZIP)

`GET /api/jobs/:id/export` streams a ZIP with this structure:

```
<jobNumber>.conddo-studio.zip
├── manifest.json           ← spec below; the source of truth on import
├── brief.md                ← human-readable rendering of the brief
├── README.md               ← "what to do with this bundle" for the staff member
├── site.json               ← snapshot of /jobs/:id/site (when builder exists)
├── ai-suggestions.json     ← snapshot of job.ai_suggestions per section
├── qa-history.json         ← every QA review with checklist + notes
├── activity.json           ← full activity log
└── assets/
    ├── <assetId>--<filename>     ← every job asset downloaded from Cloudinary
    └── …
```

Filenames in `/assets/` follow `<assetId>--<original_filename>` so re-import
can map back to the existing asset row instead of duplicating.

### 22.2 manifest.json

```jsonc
{
  "schemaVersion": 1,
  "exportedAt": "2026-06-03T12:00:00Z",
  "exportedBy": { "staffId": "uuid", "name": "Mercy Emmanuel", "email": "..." },
  "job": {
    "id": "uuid",
    "jobNumber": "STU-2026-0042",
    "version": 17,                 // job's optimistic-lock version at export
    "title": "Lagos Cuts Barbershop",
    "jobType": "WEBSITE_BUILD",
    "status": "IN_PROGRESS",
    "tenantId": "uuid",
    "brief": { /* …full JSONB… */ }
  },
  "site": { "version": 4, "status": "DRAFT" },   // brief reference; full state in site.json
  "assets": [
    { "id": "uuid", "filename": "logo.png", "bytes": 41229, "sha256": "..." }
  ],
  "checksum": "sha256:<hex>"      // SHA-256 of every file in the zip except manifest.json itself
}
```

`checksum` lets the server detect tampering on re-import.

### 22.3 Import

`POST /api/jobs/:id/import` (multipart, single `file` field — the ZIP).

```
multipart/form-data
  file: <jobNumber>.conddo-studio.zip
```

Server pipeline:

1. **Verify the bundle.** Recompute the checksum from the file tree and compare
   to `manifest.checksum`. Reject with `422 BUNDLE_TAMPERED` on mismatch.
2. **Verify the job matches.** `manifest.job.id` must equal the URL path
   `:id`. Reject `422 JOB_MISMATCH`.
3. **Verify the staff is the assignee** (or TEAM_LEAD/ADMIN). 403 otherwise.
4. **Optimistic-lock check.** If `manifest.job.version` is older than the
   current `Job.version`, return `409 STALE_BUNDLE` with the current version
   so the client can offer "force overwrite" or "fetch newer and merge".
5. **Apply, atomically (single transaction):**
   - `brief` → overwrite `job.brief` JSONB
   - `site.json` → `PUT /site` semantics (replace pages + sections + theme)
   - `ai-suggestions.json` → merge into `job.ai_suggestions`
   - Each file in `/assets/<assetId>--*`:
     - If `assetId` exists on the job → re-upload to Cloudinary with same
       `public_id` (overwrites), update `bytes`/`sha256`/`updated_at`
     - If new → upload, create a new asset row
   - Assets that exist on the job but are *missing* from the bundle are
     **kept** (import is additive — to delete, use `DELETE /assets/:id`)
6. **Increment `Job.version`** and append `JOB_IMPORTED` to the activity log
   with `{ exportedAt, fileCount, assetCount }` in the detail field.
7. Broadcast `job.imported` SSE event.

Response:
```
200 { job: <JobDetail>, applied: { brief: true, site: true, assets: { updated: 4, created: 1 } } }
```

### 22.4 Endpoint Contracts

```
GET   /api/jobs/:id/export
      Headers: Accept: application/zip
      → 200 application/zip                       streams the bundle
        Content-Disposition: attachment; filename="STU-2026-0042.conddo-studio.zip"
      → 404 if job not found
      → 403 if caller has no access to the job
      Activity log: JOB_EXPORTED { exportedBy, bytes }

POST  /api/jobs/:id/import   (multipart)
      Body: file=<zip>
      → 200 { job, applied }
      → 409 STALE_BUNDLE      (manifest.job.version older than current)
      → 422 BUNDLE_TAMPERED   (checksum mismatch)
      → 422 JOB_MISMATCH      (manifest job id ≠ url id)
      → 413 BUNDLE_TOO_LARGE  (max 256 MB by default — STUDIO_IMPORT_MAX_BYTES)
```

### 22.5 Limits & Safety

```
STUDIO_EXPORT_ASSET_INLINE_MAX_BYTES = 50 MB     (per-asset; larger ones are skipped
                                                  with a manifest note and a
                                                  refreshable Cloudinary link)
STUDIO_IMPORT_MAX_BYTES              = 256 MB    (whole bundle)
```

- Exports stream — never buffer the whole zip in memory.
- `assets/` files are pulled from Cloudinary's private URL on the server side,
  not via redirect — so the bundle works offline.
- Cloudinary's `public_id` for each asset is preserved across export/import,
  so re-uploading overwrites in place (no orphaned uploads, no URL changes).

---

## 23. Implementation Rules for Agents

Read these before writing any code. These rules are non-negotiable.

```
DATA RULES
  All tables must be in the studio schema, not public.
  No direct UUID construction from strings — always UUID.fromString().
  All timestamps must be TIMESTAMPTZ, never TIMESTAMP.
  Staff can only see jobs matching their skills array.
  Staff can only modify jobs assigned to them (except TEAM_LEAD/ADMIN).

AUTH RULES
  Studio JWT is completely separate from platform JWT.
  Never use platform tenant JWT for staff authentication.
  Refresh tokens must be hashed in the database — never stored plain.
  All sensitive endpoints must have @PreAuthorize annotation.

JOB RULES
  Only one staff member can hold a job at a time — enforce at DB level
    with optimistic locking on the status field.
  SLA clock starts the moment a job is claimed (assigned_at).
  revision_count increments every time a job is returned — never resets.
  Jobs can only move forward in the lifecycle — never backwards
    except REVISION which goes back to IN_PROGRESS.

AI RULES
  Claude API calls must have a 30-second timeout.
  Failed Claude calls must log the error but never fail the request.
  If Claude is unavailable, return empty suggestions with a flag.
  Never return Claude output directly to the frontend without parsing.
  All prompts must include COPY_RULES to prevent AI slurp language.

SSE RULES
  SSE connections must be cleaned up on disconnect.
  Never send SSE events to staff who are not online (no emitter).
  Heartbeat must fire every 30 seconds to prevent connection timeout.
  SSE reconnection must be handled client-side with exponential backoff.

FRONTEND RULES
  All SLA countdowns must use font-mono (Geist Mono).
  Never use Lorem Ipsum — use realistic Nigerian business names.
  Status badges must always show both colour AND text label.
  The claim confirmation must prevent accidental double-claims.
  QA approve button must be disabled until all required checks are ticked.

PERFORMANCE RULES
  Never run N+1 queries — use JOIN FETCH or batch loading.
  Performance cache recalculates daily — never on every request.
  SLA monitor runs every 10 minutes — not more frequently.
  SSE broadcasts should be async — never block the request thread.
```

---

*Conddo Studio — Internal Operations Platform · Handel Cores · Version 1.0 · 2026 · Confidential*
