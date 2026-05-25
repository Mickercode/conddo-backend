# Conddo.io — Service Topology: Control / Production / Runtime

> **Status:** design proposal (2026-05-25). Companion to `ARCHITECTURE.md` (the
> module/registry/event model) and `conddo_infrastructure.md` (§12–13 Studio/Jobs).
> Resolves a recurring conflation — "Website", "Jobs", and "Marketing" are treated
> as one thing, but they live on **three different planes** with different runtime
> and security profiles. Service boundaries follow those planes, not features.

---

## 1. Why this document

The questions that triggered it:

1. *Is Website a service of its own? Should all Jobs (website, ads, graphic design) be one service?*
2. *Can owners import an existing website, reconfigure it, and manage their site + other jobs?*
3. *What's needed to make Marketing work fully?*

The short answers: Website's **runtime** should be its own service (it isn't yet);
the **jobs/creative** engine already is one service (`conddo-studio`) and already
spans website + ads + design + copy; and Marketing is data-complete but
delivery-empty. The rest of this doc lays out the target so we build toward it
deliberately instead of accreting endpoints.

---

## 2. The three planes

| Plane | Responsibility | Runtime profile | Service | State |
|---|---|---|---|---|
| **Control** | Owner/staff manage everything; the dashboard API | Authenticated, tenant-scoped, request/response | `conddo-api` | ✅ live |
| **Production** | The work gets done: website builds, ads, graphics, brand kits, copy — by internal staff | Internal staff auth, job lifecycle, SLAs, QA | `conddo-studio` | ✅ live (engine), ⬜ owner-facing |
| **Runtime** | The published artefact is **served**: live sites on subdomains + custom domains | Public traffic, always-on, SSL/CDN, high read volume | `conddo-sites` | ⬜ **not built** |

```
                         ┌──────────────────────────────────────┐
   Owner / Staff ──────► │  CONTROL  ·  conddo-api (dashboard)    │
   (app.conddo.io)       │  CRM·Orders·Payments·Marketing·Website │
                         └───────────────┬───────────────────────┘
                       request a job /   │   manage / view status
                       publish result    │
        ┌──────────────────────────────┐ │ ┌──────────────────────────────┐
        │ PRODUCTION · conddo-studio    │◄┘►│ RUNTIME · conddo-sites (NEW)  │
        │ jobs engine: WEBSITE_BUILD,   │   │ stores + SERVES the live site │
        │ GRAPHIC_DESIGN, AD_CREATIVE,  │   │ *.conddo.io + custom domains  │
        │ BRAND_KIT, CONTENT_WRITING…   │   │ import · builder/config API   │
        │ internal staff · SLA · QA     │──►│ build job publishes here      │
        └──────────────────────────────┘   └───────────────────────────────┘
   Internal staff (jobs.conddo.io)            Public visitors (business.conddo.io)
```

**The boundary rule:** split a service out when its **runtime or security profile**
differs (public site-serving vs internal staff jobs vs tenant dashboard) — *not*
because a feature is big. "Owners manage their website + ads + graphics" is more
**API surface in `conddo-api`** plus **windows into Studio/Sites**, not a fourth service.

---

## 3. `conddo-studio` is already the "all jobs" service

The build engine the question imagines mostly exists. `studio.job_types` already seeds:

| Job type | Discipline | SLA | QA |
|---|---|---|---|
| `WEBSITE_BUILD` | Developer | 48h | 8-point checklist |
| `WEBSITE_REVISION` | Developer | 24h | yes |
| `GRAPHIC_DESIGN` | Designer | 24h | yes |
| `AD_CREATIVE` | Designer | 12h | yes |
| `BRAND_KIT` | Designer | 72h | yes |
| `CONTENT_WRITING` | Writer | 24h | yes |

Plus a generic **11-state machine**, role-based assignment, claim/QA/approve/deliver,
SLA tones, and performance — all generic over `job_type_id`. **Adding a new kind of
work is a row in `job_types`, not new code.**

What's missing for the owner-facing vision:

- **Owner-initiated jobs.** Today only internal `TEAM_LEAD/ADMIN` create jobs
  (`/api/jobs/admin/jobs`). Owners need a path to *request* work (scoped to their tenant)
  that lands in the same queue. → §5.
- **Tenant scoping of jobs.** `studio.jobs.tenant_id` exists but is a **soft reference**
  (no FK; Studio has no RLS — it's internal). Owner-visible job lists must filter by it
  through a trusted, tenant-scoped read path (via `conddo-api`, not direct Studio access).
- **Asset storage.** Deliverables (designs, ad creatives, site bundles) need MinIO/S3 —
  deferred in the current Studio build.

> Naming note: `conddo_infrastructure.md` lists §12 "Conddo Studio" and §13 "Jobs
> Board" as two backends; we built **one** service (`conddo-studio`, `studio`+`jobs`
> schemas). Fold those two sections into one in the infra doc. (Open decision, §10.)

---

## 4. The hand-off seam (Control ↔ Production)

This is the single most valuable thing to wire, and it's currently a dead end:
`POST /api/v1/website/change-requests` records a `website_change_requests` row as
`PENDING` **and stops**. It's *supposed* to create a Studio job.

**Target flow:**

```
Owner (conddo-api)                    conddo-studio                 conddo-sites
  │ POST /website/change-requests        │                              │
  │  area, details                       │                              │
  ├──── create job (WEBSITE_REVISION) ──►│ job QUEUED→AVAILABLE          │
  │  ◄── studio_job_id ──────────────────┤                              │
  │  (store on change_request,           │  staff claim→build→QA→approve │
  │   status syncs from job events)      │                              │
  │                                      ├──── publish bundle ─────────►│ site updated
  │  ◄──────── status: DONE ─────────────┤◄──── live ───────────────────┤
```

**Mechanism — three options:**

| Option | How | Fit |
|---|---|---|
| **Event bus** (Redis Pub/Sub) | `conddo-api` publishes `WebsiteChangeRequested`; Studio consumes; Studio publishes `JobApproved`; api updates the request | Matches `ARCHITECTURE.md` §9 (P4 "events are the integration layer"). Best long-term; needs Redis provisioned. |
| **Signed service call + webhook** | api → Studio `POST /jobs` (service token); Studio → api webhook on status change | Works today on Render with no Redis; simplest to ship. **Recommended first.** |
| **Shared-DB read** | api reads `studio.jobs` directly | Rejected — couples the services, breaks the no-shared-schema split and Studio's no-RLS assumption. |

Because the tenant link is soft (no cross-service FK), the contract is **`tenant_id`
+ `studio_job_id` carried in the message**, reconciled on each side. Start with the
signed call + webhook; lift to the event bus when Redis lands.

---

## 5. `conddo-sites` — the website runtime (new service)

The piece that justifies "Website as its own service." Distinct because it serves
**public traffic** with a totally different profile from the dashboard.

**Responsibilities**
- Store each tenant's **site definition**: sections, content blocks, theme/branding, nav, SEO.
- **Serve** the live site at `{slug}.conddo.io` and connected custom domains (SSL, caching/CDN).
- A **builder/config API** so owners self-manage (or hand off to a Studio build for the pro touch).
- **Import** an existing site (§6).
- Receive **published bundles** from Studio build jobs (§4).

**Data model sketch** (tenant-scoped, own schema/DB):
```
sites(id, tenant_id, status, theme, seo, published_at, …)
site_sections(id, site_id, type, position, content JSONB, …)   -- maps to VerticalConfig websiteSections
site_domains(id, site_id, host, kind[subdomain|custom], ssl_status, verified_at)
site_assets(id, site_id, key, kind, url)                       -- MinIO/S3
site_revisions(id, site_id, snapshot JSONB, source[builder|studio|import], created_at)
```

**Serving path:** wildcard DNS `*.conddo.io` (and custom domains) → Render/Nginx →
`conddo-sites` resolves the host → renders the published revision. The existing
`SubdomainTenantResolver` in `conddo-api` already proves the host→tenant resolution
pattern; `conddo-sites` owns the *serving* of it.

**Relationships**
- `conddo-api` → `conddo-sites`: owner reads/edits config; the `/website/*` endpoints
  become a thin proxy/aggregate over Sites (status, sections, analytics, domain).
- `conddo-studio` → `conddo-sites`: an approved `WEBSITE_BUILD`/`REVISION` job publishes
  a revision into Sites.
- The current `website_status` / `website_published_at` on `tenants` is a stopgap; the
  authoritative state moves to `conddo-sites` once it exists (keep the columns as a cache).

---

## 6. Importing an existing website

Three modes, increasing fidelity/effort:

| Mode | What happens | Owner gets | Effort | Use when |
|---|---|---|---|---|
| **Template import** | Pull branding (logo, colours, fonts) + copy as a starting point for a Studio build | A head-start, fully rebuilt on Conddo | Low | They want a fresh, managed rebuild |
| **Takeover** (recommended default) | Crawl the site, extract content/structure into Conddo's **section model**, rebuild editable | A real, **reconfigurable** Conddo site | High | They want to manage it on Conddo |
| **Proxy / bridge** | Point their domain at Conddo, reverse-proxy the existing site, replace pages gradually | Zero-downtime cutover, then migrate | Medium | Big/complex sites; phased migration |

**Takeover pipeline:** `fetch URL → render (handle JS sites) → extract (readability/DOM →
blocks) → map to Conddo section types → import assets to MinIO → owner/Studio review (QA)
→ publish revision`.

**Risks to design for:** JS-rendered SPAs (need a headless render), asset/CSS fidelity,
content ownership/legal, and broken extraction → always gate behind a **review step**
(naturally a Studio QA job) before going live. Proxy mode is the safe bridge while Takeover
extraction matures.

---

## 7. Marketing — what it takes to work *fully* (§11.8)

Marketing is **~70% data-ready, ~0% delivery-ready**: posts, campaigns, leads,
connections, and KPI cards all store and display, but every external integration is
stubbed (`adSpend` reads 0, `/marketing/connections` just records a handle, no `/ads`
endpoint, no real sends).

**Shared infrastructure (most channels block on these):**
- **Media storage + CDN** (MinIO/S3) — post images, ad creatives, graphics.
- **Queue + scheduler** (Redis + worker, or Quartz) — scheduled posts, drip sends, ads/metric sync.
- **Encrypted token vault** — OAuth tokens at rest.
- **Analytics ingestion** — replace KPI zeros with real reach/engagement/opens/clicks/conversions.
- **Consent/compliance** — unsubscribe, NCC DND opt-out for SMS, SPF/DKIM/DMARC on the sender domain.

**Per channel:**
- **Social publishing** — real OAuth + APIs (Meta IG/FB Graph, X, LinkedIn); a publish
  worker firing at `scheduledAt`; metric pull-back.
- **Email campaigns** — a bulk ESP (Brevo/SendGrid *broadcasts* — transactional Resend ≠ bulk),
  templates, CRM-segment targeting, open/click tracking, bounce/complaint webhooks, unsubscribe.
- **SMS campaigns** — Termii/Brevo bulk (Brevo sender exists), sender-ID registration, delivery receipts, opt-out.
- **Ads** — the missing `GET/POST /api/v1/marketing/ads` + a `marketing_ads` table + the
  **Meta Marketing API** (ad-account link, campaign/insights sync, rate limits). Feeds the real `adSpend`.
- **Leads** — auto-capture from **website contact forms** (ties into `conddo-sites`) and
  social lead ads, not just manual; nurture sequences (needs the scheduler).
- **AI copy assistant** — `POST /marketing/assistant/generate` via the Claude API
  (captions/email/SMS/ad), human-in-the-loop. Endpoint absent today.

**Dependency order:** media storage + queue/scheduler **first**, then one channel
end-to-end (social or email) to prove the publish-worker + metrics loop, then the rest.
Graphic/ad creatives are produced as **Studio jobs** (§3) and consumed here.

---

## 8. Recommended build sequence

1. **Wire the hand-off + open owner-initiated jobs (§4, §3).** Smallest step that makes
   "manage all jobs" real; reuses the existing engine. Signed-call + webhook first.
2. **`conddo-sites` MVP (§5).** Serve a published site on the subdomain from a stored
   definition + the owner config API; `conddo-api` `/website/*` becomes a proxy.
3. **Import (§6).** Template + Proxy first (low risk), Takeover extraction as it matures.
4. **Marketing infra then channels (§7).** MinIO + queue, then social/email end-to-end, then ads + AI.

---

## 9. Open decisions

- **Studio vs Jobs Board naming** — collapse infra §12/§13 into the single `conddo-studio` service.
- **Cross-service transport** — webhooks now vs Redis event bus later (ARCH §9).
- **Hosting model** — Render multi-service (current) vs the Hetzner/Docker-Compose model in `conddo_infrastructure.md`. `conddo-sites` (public traffic, SSL, custom domains) is the one that most stresses this choice.
- **Self-edit vs staff-build** — does the owner edit the site directly in `conddo-sites`, only request Studio jobs, or both (self-serve with an optional pro build)?
- **Custom-domain SSL automation** — Let's Encrypt/ACME per tenant domain, and who terminates it (Render, Nginx, or Sites).
