# Backend Status — handoff to frontend

**Updated:** 2026-06-03
**Owners:** Mickercode + Claude (backend work coordinated via Claude Code)

This is the backend's mirror of [FRONTEND_STATUS.md](./FRONTEND_STATUS.md). It
tells the frontend teams:

1. What we just shipped on both backends (so screens already wired against
   "coming soon" placeholders can light up).
2. What new endpoints, SSE events, and env vars now exist (so you can plan
   wire-ups without grepping commit messages).
3. What's still pending and roughly when.

The full contract still lives in [ACTION_LIST.md](./ACTION_LIST.md) (tenant API),
[conddo_studio_combined.md](./conddo_studio_combined.md) (Studio API), and the
per-module docs they reference. This doc is a **delta + roadmap snapshot**, not
a source of truth.

---

## 1. What just shipped (Phase 1 — Studio Phases 6 / 7 / 8)

| Slice | What it adds | Tests | Status |
|---|---|---|---|
| A — Auto-create + WebsiteTypeResolver | On `TenantActivated`, the platform fires a `WEBSITE_BUILD` Studio job; the resolver picks `LANDING_PAGE` / `BOOKING_FOCUSED` / `ECOMMERCE` / `MULTI_PAGE` from the brief + plan tier. | 48 conddo-api | ✅ merged to main |
| B — Studio asset uploads | `POST/GET/DELETE /api/jobs/{jobId}/assets` (multipart, Cloudinary). 10 MB cap, image + PDF only. | 5 e2e | ✅ merged to main |
| C — AI image ranker | `POST /api/jobs/{id}/rank-images` (Claude vision). Sorts URLs by score; failed URLs sink to score 0. | 2 unit + e2e | ✅ merged to main |
| **D.1 — SSE event hub** | Long-lived `GET /api/jobs/events` (SSE) with role/skill filtering + heartbeat. Every job transition fires a typed `JobLifecycleEvent` AFTER_COMMIT so subscribers never see half-applied state. | 8 unit + e2e | ✅ merged to main |
| **D.2 — Brevo email mirror** | Three high-priority events also email the recipient (Job Reassigned, Revision Requested, Escalated → all leads + admins). | 7 unit | ✅ merged to main |
| **E — SLA monitor** | `@Scheduled` (5 min) walks active jobs, broadcasts an `sla.tick` snapshot to leads + admins, auto-escalates anything past deadline (which reuses the D pipeline for free). | 4 unit | ✅ merged to main |
| **F.1 — Job-type CRUD** | ADMIN can add new types, tune per-type SLA hours, retire types via soft-delete. | 11 unit + e2e | ✅ merged to main |
| **F.2 — Persistent performance** | Daily 02:00 UTC recalc into `jobs.staff_performance` (the V1 table that was idle). New monthly snapshots + 12-month history. | 7 unit | ✅ merged to main |
| **F.3 — Design Standard Library** | ADMIN-curated reference content (palettes / layouts / copy patterns / typography) per vertical. AI prompt-injection of this content is the next sub-slice. | 11 unit | ✅ merged to main |

**Test totals as of this doc:** **48 conddo-api + 61 conddo-studio = 109 green.**

The full Studio test suite runs in **~90 s** (the 60 s e2e StudioJobsFlowTest
under Testcontainers is the heaviest item; everything else is sub-second).

---

## 2. New conddo-studio endpoints (since the FE status doc you last read)

### Live SSE stream — **wire this for the board + notifications**

| | Method | Endpoint | What it returns |
|---|---|---|---|
|🆕| GET | `/api/jobs/events` | Server-Sent Events stream; one connection per browser tab. |

Event names + payload shapes (all defined in
[JobLifecycleEvent.java](conddo-studio/src/main/java/io/conddo/studio/sse/JobLifecycleEvent.java)):

| Event | Recipients (server-side filter) | Payload |
|---|---|---|
| `hello` | The connecting staff member (one-shot on subscribe) | `{staffId, role, at}` |
| `heartbeat` | Everyone (every 30 s, keeps proxies from closing the conn) | `{at}` |
| `job.created` | Skill matches the new job's `jobTypeId` (empty skills = all) | `{jobId, jobNumber, jobTypeId, status, slaTone}` |
| `job.claimed` | Same as `job.created` (so other browsers drop it from "available") | `{jobId, jobNumber, jobTypeId, staffId}` |
| `job.started` | The assignee | `{jobId, jobNumber, staffId}` |
| `job.submitted` | QA_REVIEWER + the assignee | `{jobId, jobNumber, jobTypeId, staffId}` |
| `job.approved` | The assignee | `{jobId, jobNumber, assignedTo}` |
| `job.revision_requested` | The assignee | `{jobId, jobNumber, assignedTo, feedback}` |
| `job.reassigned` | The new assignee | `{jobId, jobNumber, newStaffId}` |
| `job.escalated` | TEAM_LEAD + ADMIN | `{jobId, jobNumber, reason}` |
| `job.sla_extended` | The assignee (if any) | `{jobId, jobNumber, addedHours, assignedTo}` |
| `sla.tick` | TEAM_LEAD + ADMIN (every 5 min, only when AMBER/RED jobs exist) | `Array<{jobId, jobNumber, tone, hoursToDeadline, assignedTo}>` |
| `notification.created` | The targeted staff member | `{staffId, notificationId, type, title, message, jobId, jobNumber}` |

**EventSource note** — browsers' built-in `EventSource` can't set
`Authorization` headers. You'll need
[`@microsoft/fetch-event-source`](https://www.npmjs.com/package/@microsoft/fetch-event-source)
or similar to pass the STUDIO_JWT. Sample code:

```ts
import { fetchEventSource } from "@microsoft/fetch-event-source";

await fetchEventSource("/api/jobs/events", {
  headers: { Authorization: `Bearer ${accessToken}` },
  onmessage(ev) {
    switch (ev.event) {
      case "job.created": invalidate(["jobs", "available"]); break;
      case "job.claimed": invalidate(["jobs", "available"]); break;
      case "job.submitted": invalidate(["qa", "queue"]); break;
      case "notification.created": invalidate(["notifications"]); break;
      // ... etc.
    }
  },
  onerror(err) { /* exponential backoff reconnect */ }
});
```

The SSE connection survives 30 min idle; the server sends a `heartbeat` every
30 s to keep load balancers from cutting it. On reconnect, hit the same
endpoint — `subscribe()` is idempotent per tab.

### Email mirror — no FE work, just visibility

Three lifecycle events also send an email to the recipient. The FE doesn't
need to do anything; it's a parallel channel for offline staff.

- `JobReassigned` → "New job assigned: WB-1001" to the new owner.
- `JobRevisionRequested` → "Revision requested: WB-1001" + feedback quoted, to the assignee.
- `JobEscalated` → "Escalation: WB-1001" to every active TEAM_LEAD + ADMIN.

If `STUDIO_EMAIL_PROVIDER` is unset, the mailer falls back to the logger
adapter (logs what would have been sent) — so dev / pre-secrets Render boots
keep working. See "Env vars" below.

### Notifications — mark-all-read

| | Method | Endpoint | What it returns |
|---|---|---|---|
|🆕| PATCH | `/api/jobs/notifications/read-all` | `{updated: <int>}` — how many were marked. |

For the notifications drawer's "mark all read" button. The FE already shows
the unread count via `/api/jobs/notifications?unread=true`; this drops it
to 0 in one round-trip.

### Admin — job-type CRUD (SLA settings live here too)

| | Method | Endpoint | Auth |
|---|---|---|---|
|🆕| GET | `/api/jobs/admin/job-types` | TEAM_LEAD + ADMIN |
|🆕| POST | `/api/jobs/admin/job-types` | ADMIN only |
|🆕| PATCH | `/api/jobs/admin/job-types/{id}` | ADMIN only |
|🆕| DELETE | `/api/jobs/admin/job-types/{id}` | ADMIN only (soft) |

Create body: `{id, displayName, colour, assignedToRoles[], slaHours, qaRequired, qaChecklist[]}` —
`id` must be UPPER_SNAKE_CASE (3-32 chars). Tune SLA hours per type via
PATCH `{slaHours: 8}`. Hard-delete is refused because every `studio.jobs`
row holds an FK to `job_types(id)`.

### Admin — Design Standard Library

| | Method | Endpoint | Auth |
|---|---|---|---|
|🆕| GET | `/api/jobs/admin/design-standards?kind=…` | TEAM_LEAD + ADMIN |
|🆕| GET | `/api/jobs/admin/design-standards/{id}` | TEAM_LEAD + ADMIN |
|🆕| POST | `/api/jobs/admin/design-standards` | ADMIN only |
|🆕| PATCH | `/api/jobs/admin/design-standards/{id}` | ADMIN only |
|🆕| DELETE | `/api/jobs/admin/design-standards/{id}` | ADMIN only (soft) |

Create body: `{vertical?, kind, name, description?, content?}`. `kind` ∈
`PALETTE | LAYOUT | COPY_PATTERN | TYPOGRAPHY`. `vertical: null` (or omitted)
means "applies to every vertical".

**Next slice** wires this into the AI prompts: the Copy Generator + Palette
Generator will read the active standards for the job's vertical and ground
their suggestions on them.

---

## 3. New conddo-api side — auto-create the Studio job on signup

When `TenantService.create(...)` or `provisionFromRegistration(...)` commits, the
platform now publishes a `TenantActivatedEvent` (Spring). The
`TenantActivationListener` (AFTER_COMMIT, REQUIRES_NEW) loads the tenant,
asks `WebsiteTypeResolver` for the recommended website type, then calls
`StudioJobGateway.createJob` over HTTP with the brief.

Pure rule-based resolution (no AI in the hot path):

| Vertical category | Plan | Verdict |
|---|---|---|
| `professional` or `beauty` (any plan) | — | `BOOKING_FOCUSED` |
| `retail` + `pro`/`enterprise` | — | `ECOMMERCE` |
| `starter` + simple brief (1 service, no booking flag) | — | `LANDING_PAGE` |
| Everything else | — | `MULTI_PAGE` |

The brief that lands in Studio includes `websiteType` + `recommendedSections`
so the developer picks up an opinionated job, not a blank one. FE-side:
nothing changes; this fires server-to-server.

If Studio is down, the signup still succeeds — the listener swallows the
exception and logs it. There's no retry queue yet; a missing job needs a
manual `POST /api/jobs/intake` from ops.

---

## 4. New env vars (set these before flipping the FE wires)

### conddo-studio on Render

| Var | Purpose | Default |
|---|---|---|
| `STUDIO_EMAIL_PROVIDER` | Set to `brevo` to enable the email mirror. Unset = logger fallback. | `` (off) |
| `STUDIO_EMAIL_API_KEY` | Brevo API key. | `` |
| `STUDIO_EMAIL_FROM` | Verified sender address (e.g. `studio@conddo.io`). | `` |
| `STUDIO_EMAIL_FROM_NAME` | Display name on the From: header. | `Conddo Studio` |
| `STUDIO_EMAIL_BASE_URL` | Override Brevo endpoint (rare). | `https://api.brevo.com` |
| `STUDIO_SLA_MONITOR_INTERVAL_MS` | How often the SLA monitor walks the board. | `300000` (5 min) |
| `STUDIO_SLA_MONITOR_INITIAL_DELAY_MS` | First scan delay after boot. | `60000` (1 min) |
| `STUDIO_PERFORMANCE_CRON` | Daily recalc cron (UTC). | `0 0 2 * * *` (02:00 UTC) |

The full file is at
[infra/conddo-studio.env.example](./infra/conddo-studio.env.example) — every
var has an inline comment + "leave blank to dry-run" notes where applicable.

### conddo-api on Render — no new vars

The auto-create-on-signup flow uses the existing `STUDIO_BASE_URL` +
`STUDIO_SERVICE_TOKEN` pair already wired for `POST /api/jobs/intake`.

---

## 5. Endpoint contract reminders (no change, just re-stated)

For the new endpoints + the existing ones:

- **Envelope:** `{success, data, meta?, error{code,message,details[]}}`. Both
  backends already match the FE's `lib/api/client.ts` expectation. SSE payloads
  are JSON in the `data:` line (the standard SSE wire format) — the **inner**
  payload is the raw event record, **not** the envelope.
- **Studio session tokens stay in the body** (`{accessToken, refreshToken}`),
  refresh via `POST /api/jobs/auth/refresh {refreshToken}`. Unchanged.
- **CORS** on the Studio service supports both `STUDIO_CORS_ALLOWED_ORIGINS`
  (exact) + `STUDIO_CORS_ALLOWED_ORIGIN_PATTERNS` (wildcards for Vercel
  previews). The SSE endpoint is allowed under the same rule (it's a regular
  GET; only the `Content-Type: text/event-stream` is special).

---

## 6. Pending — what's still on the backlog

Ranked by FE impact, mirroring §4 of FRONTEND_STATUS.md:

1. **Google Sign-in (conddo-api)** — FE buttons + popup flow shipped; backend
   still 404s. Spec in [ACTION_LIST.md §1a](./ACTION_LIST.md). Small slice (1
   schema column, 2 endpoints, ID-token verify via `google-api-client`). 
   **Not yet started.**
2. **Studio Builder API (`/jobs/:id/site/**`)** — Builder route is a "coming
   soon" placeholder on the Studio FE. AI section endpoints already exist
   (`/ai-suggest`, `/palette`, `/rank-images`) but write into the legacy
   `jobs.ai_suggestions` blob; the spec wants a proper `site_sections.content`
   JSONB. Spec in `conddo_studio_combined.md §21`. **Not yet started.**
3. **Studio Export / Import (`GET /jobs/:id/export`, `POST /jobs/:id/import`)** —
   Requested by ops for offline work. ZIP streamer + Cloudinary
   server-side download. Independent of #2. Spec in `conddo_studio_combined.md §22`.
   **Not yet started.**
4. **DSL → AI prompt injection (sub-slice of F.3)** — wire
   `DesignStandardService.forVertical(...)` into the Copy + Palette
   generators so admins' curated standards actually ground Claude's output.
   Code-only; no FE work needed.
5. **Slice G — Phase 10 hardening** — missing-coverage tests, the
   50-concurrent-SSE load test, and a cross-staff security audit. **Up next
   in the queue.**

After this Phase, the user has explicitly queued **Payment Service (RoutePay
resumption)** as the next workstream.

---

## 7. Operational notes that are different now

- **`@EnableScheduling`** is now on `StudioApplication`. Two scheduled jobs
  live in the service:
  - `SseService.heartbeat()` (every 30 s, in-memory, cheap)
  - `SlaMonitorService.tick()` (every 5 min, one DB query + N broadcasts)
  - `PerformanceService.dailyRecalc()` (cron 02:00 UTC, one query per active
    staff member)
  None of these would noticeably load Render's free tier — but if you see
  the Studio service warmer than expected after idle, the heartbeat is the
  reason. There's no way to start an SSE stream from the FE without keeping
  the Render instance awake.

- **Flyway migration count:** Studio is now at **V3** (`V1__studio_schema`,
  `V2__job_ai_suggestions`, `V3__design_standards`). The post-deploy schema
  log should show three applied migrations; "Migrated to version V3" means
  you're on the right branch.

- **`jobs.staff_performance` is now populated.** Empty rows mean either
  (a) no recalc has fired yet (wait until 02:00 UTC or hit
  `PerformanceService.recalc` manually), or (b) the staff member is inactive
  (the daily job skips them deliberately).

---

## 8. How to confirm everything's wired right

A 60-second smoke test from the FE:

1. Open the Studio in a browser tab, log in as ADMIN.
2. DevTools → Network → filter `/events`. After login the FE should open one
   long-lived 200 with `Content-Type: text/event-stream`. The first frame
   should be `event: hello`; every 30 s a `heartbeat`.
3. In a second tab, log in as a DEVELOPER (skills include `WEBSITE_BUILD`).
   Have the admin create a Website Build job. The developer's tab should
   receive a `job.created` event in well under a second.
4. The developer claims the job. The admin's first tab should receive
   `job.claimed` immediately.
5. Hit `PATCH /api/jobs/notifications/read-all` from DevTools — the
   notification bell drops to 0 without a refresh.

If steps 2-4 work, SSE is fully wired. If step 1 returns 200 but no events
ever land, check that the FE is sending the JWT (browser-builtin EventSource
can't — must use `fetch-event-source` or similar).

---

## 9. Where to look when something breaks

Same as the FE side, in reverse:

1. **Studio backend logs (Render)** — every `JobLifecycleEvent` publish and
   SSE broadcast is logged at DEBUG. Look for `Dropping SSE connection` if
   pushes are failing; that's how we surface dead emitters.
2. **`SseService.connectionCount()`** is exposed in package-private form for
   tests; if you need a runtime view, the next slice can add an admin
   `/actuator`-style endpoint. Ping us if you want it.
3. **`StudioEmailNotifier` errors are logged at ERROR but never propagate.**
   A failed Brevo call won't break the JobService flow; check the email
   provider's dashboard for the actual delivery status.

For the bigger picture, three docs in order of precedence:

1. [ACTION_LIST.md](./ACTION_LIST.md) — canonical spec, including the items
   not yet built.
2. [conddo_studio_combined.md](./conddo_studio_combined.md) — Studio-specific
   spec details (§21 builder, §22 export/import).
3. This doc + [FRONTEND_STATUS.md](./FRONTEND_STATUS.md) — current state of
   "what's wired".
