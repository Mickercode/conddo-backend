
# Frontend Status — handoff to backend

**Updated:** 2026-06-03 (post backend-handoff sync)
**Owner of the FE codebases:** Mickercode (UI work coordinated via Claude Code)

This is a living status doc. It tells the backend team:

1. What the two frontends currently consume (so any breaking change to those
   endpoints will surface here first).
2. What the frontends are blocked on (so the backend can prioritise).
3. Where the contracts live (so nothing here duplicates `ACTION_LIST.md` or
   `conddo_studio_combined.md` — those remain the source of truth).

If you change an endpoint contract, search this doc for the path and update the
"FE call site" entry so the next person knows what to retest.

### What changed since the last update

| Delta | Commit | Status |
|---|---|---|
| **SSE wired** — singleton stream, replaces 30s polling, live job-board / QA-queue / admin-dashboard refresh, `notification.created` increments bell | conddo-studio `37cf235` | ✅ deployed |
| **Job Types admin page** — `/admin/job-types`, full CRUD + SLA tuning + QA checklist editor, role-gated UI (ADMIN mutates, TEAM_LEAD reads) | conddo-studio `fb8aa40` | ✅ deployed |
| **Platform Admin spec'd** — new §23 + Phase 13a/13b in `conddo_studio_combined.md`. Studio ADMIN gets cross-tenant management (tenants + tenant users). Backend not yet built. | backend `e1dca7d` | 📋 spec only |
| **Operational note added** — `STUDIO_BASE_URL` + `STUDIO_SERVICE_TOKEN` must be set on `conddo-backend` Render service or signup → Studio job hand-off silently no-ops. Hit this in production. | backend `e1dca7d` | 📋 ops action |

The new backend endpoints from the last handoff (SSE, Job Types CRUD,
notification mark-all-read, performance recalc, email mirror) are now all
consumed. **Design Standards CRUD** (also shipped backend-side) is the next
FE slice — same shape as Job Types, just listing/editing palettes / layouts
/ copy patterns / typography by vertical.

---

## 1. Frontends

| App | Repo | Local Path | Deployed | Backend it talks to | 
|---|---|---|---|---|
| **conddo-app** (tenant-facing) | github.com/Mickercode/conddo.io | `conddo-app/` | https://conddo-io.vercel.app | https://conddo-backend.onrender.com (this repo, `conddo-api` module) |
| **conddo-studio** (internal ops) | github.com/Mickercode/conddo-studio | `conddo-studio/` | https://conddo-studio.vercel.app | https://conddo-studio.onrender.com (this repo, `conddo-studio` module) |

Both are Next.js 14 (App Router) + TypeScript + Tailwind. Both deploy on
Vercel; both have CORS allow-lists configured on Render. CORS pattern
support (origin wildcards) is live on both backend services — see
`SecurityConfig.corsConfigurationSource` in each.

---

## 2. conddo-app — endpoints consumed

Every endpoint below is hit from the deployed frontend right now. If you remove
or rename one, expect the FE to break unless we coordinate.

### Auth (`/auth/**` — public; `/api/v1/me` — authenticated)

| Endpoint | FE call site |
|---|---|
| POST `/auth/login` | `lib/api/account.ts` → `login()` |
| POST `/auth/refresh` | `lib/api/client.ts` → `refreshAccessToken()` (silent retry) |
| POST `/auth/logout` | `lib/api/account.ts` → `logout()` |
| POST `/auth/register/start` | `lib/api/account.ts` → `registerStart()` |
| POST `/auth/register/verify` | `lib/api/account.ts` → `registerVerify()` |
| POST `/auth/register/resend` | `lib/api/account.ts` → `registerResend()` |
| POST `/auth/register/complete` | `lib/api/account.ts` → `registerComplete()` |
| POST `/auth/forgot-password` | `lib/api/account.ts` → `forgotPassword()` |
| POST `/auth/reset-password` | `lib/api/account.ts` → `resetPassword()` |
| GET `/api/v1/me` | `lib/api/account.ts` → `getMe()`, `meQuery` |

### Tenant scoped (`/api/v1/**` — Bearer token, RLS-scoped)

All the dashboard modules call into their respective `/api/v1/*` controllers:

| Area | FE file | Backend controller |
|---|---|---|
| Customers | `lib/api/customers.ts` | `CustomerController` |
| Orders + stages | `lib/api/orders.ts` | `OrderController`, `OrderStageController` |
| Bookings + public booking page | `lib/api/bookings.ts`, `lib/api/public-booking.ts` | `BookingController`, `PublicBookingController` |
| Inventory | `lib/api/inventory.ts` | `InventoryController` |
| Payments | `lib/api/payments.ts` | `PaymentController` |
| Marketing (incl. ads/email/SMS/leads tabs) | `lib/api/marketing.ts` | `MarketingController` |
| Website | `lib/api/website.ts` | `WebsiteController` |
| Analytics | `lib/api/analytics.ts` | `AnalyticsController` |
| Dashboard summary + setup checklist | `lib/api/dashboard.ts` | `DashboardController` |
| Notifications | `lib/api/notifications.ts` | `NotificationController` |
| Search (global) | `lib/api/search.ts` | `SearchController` |
| Staff (admin only) | `lib/api/staff.ts` | `StaffController` |
| Settings (sub-tabs) | `lib/api/settings.ts` | `SettingsController` |
| Media upload (multipart) | `lib/api/media.ts`, `lib/api/client.ts#uploadFile` | `MediaController` |
| Tenant signup (public) | `lib/api/account.ts` → `signup()` | `POST /api/v1/tenants` |

### Client invariants the backend must keep honouring

These are baked into `lib/api/client.ts` and any backend change that breaks
them will silently bork the deployed FE. **Please notify before changing:**

- **Response envelope:** every response is `{ success: bool, data: T, meta?, error? }`.
  The FE throws `ApiError(error.code, error.message, status, error.details)` whenever
  `!res.ok` or `success === false`. Don't return bare bodies.
- **401 → silent refresh:** the FE retries any 401 once after `POST /auth/refresh`.
  `/auth/*` calls are excluded from this loop. If you flip an endpoint from
  "authenticated" to "permitAll" the refresh is still safe (it's only attempted
  when an access token was attached).
- **No Bearer on `/auth/*`:** the FE strips the Authorization header on
  `/auth/login`, `/auth/refresh`, `/auth/logout`, `/auth/register/*`,
  `/auth/forgot-password`, `/auth/reset-password`. Don't rely on a Bearer being
  there for those — it isn't. (This was a real bug: Spring's
  `oauth2ResourceServer` was validating stale tokens before `permitAll`. See
  commit 1561704.)
- **Refresh cookie name + scope:** the FE sets `credentials: "include"` on every
  request. Cookie is named `conddo_rt`, `Path=/auth`. Don't rename without
  warning.
- **CORS:** the FE relies on
  `CONDDO_CORS_ALLOWED_ORIGINS` (exact) +
  `CONDDO_CORS_ALLOWED_ORIGIN_PATTERNS` (wildcards) both being honoured. The
  pattern flavour is needed for Vercel preview URLs — see SecurityConfig
  commit b1bbb2f.
- **Timeouts:** every JSON request has a 45 s `AbortController` timeout
  (`REQUEST_TIMEOUT_MS`), uploads 120 s. The FE surfaces `request_timeout`
  with a friendly message rather than spinning forever. Slow endpoints
  (> 45 s synchronously) will look broken even when they eventually return.

### Pending — backend work that unblocks already-shipped FE

| Pending | FE state | Spec |
|---|---|---|
| **Google Sign-in** (POST `/auth/google` + `/auth/register/start-google`) | Buttons + popup flow shipped (ddc2232). Buttons hide when `NEXT_PUBLIC_GOOGLE_CLIENT_ID` is unset; when set they 404 against the missing endpoints with a friendly message. | `ACTION_LIST.md` §1a |

When `/auth/google` ships, **no FE changes needed** — the same POST that's
currently 404'ing starts returning a real session.

---

## 3. conddo-studio — endpoints consumed

**42 of 42 user-facing endpoints** wired to the FE client (only `/api/jobs/intake`
— the service-to-service hand-off from conddo-api — is intentionally not
consumed by the FE; it's called from conddo-api). The recent backend handoff
shipped SSE, Job Types CRUD, Design Standards CRUD, and persistent
performance — three of those four are wired below; Design Standards is the
next FE slice.

### Auth (`/api/jobs/auth/**`)

| Endpoint | FE call site |
|---|---|
| POST `/api/jobs/auth/login` | `lib/account.ts` → `login()` |
| POST `/api/jobs/auth/refresh` | `lib/api.ts` → `refreshAccessToken()` (silent retry) |
| POST `/api/jobs/auth/logout` | `lib/account.ts` → `logout()` |
| GET `/api/jobs/auth/me` | `lib/account.ts` → `meQuery` |

### Jobs

| Endpoint | FE call site |
|---|---|
| GET `/api/jobs/my-jobs` | `lib/jobs.ts` → `myJobs()` |
| GET `/api/jobs/available` | `lib/jobs.ts` → `available()` |
| GET `/api/jobs/{id}` | `lib/jobs.ts` → `get()` |
| POST `/api/jobs/{id}/claim` | `lib/jobs.ts` → `claim()` |
| PATCH `/api/jobs/{id}/start` | `lib/jobs.ts` → `start()` |
| POST `/api/jobs/{id}/submit` | `lib/jobs.ts` → `submit()` |
| POST `/api/jobs/{id}/ai-suggest` | `lib/ai.ts` → `aiApi.suggest()` |
| POST `/api/jobs/ai/palette` | `lib/ai.ts` → `aiApi.palette()` |
| POST `/api/jobs/{id}/rank-images` | `lib/ai.ts` → `aiApi.rankImages()` |

### QA

| Endpoint | FE call site |
|---|---|
| GET `/api/jobs/qa/queue` | `lib/qa.ts` → `qaApi.queue()` |
| POST `/api/jobs/qa/{id}/start` | `lib/qa.ts` → `qaApi.start()` |
| POST `/api/jobs/qa/{id}/approve` | `lib/qa.ts` → `qaApi.approve()` |
| POST `/api/jobs/qa/{id}/return` | `lib/qa.ts` → `qaApi.returnForRevision()` |
| GET `/api/jobs/qa/{id}/scan` | `lib/qa.ts` → `qaApi.scan()` |

### Admin (TEAM_LEAD / ADMIN)

| Endpoint | FE call site |
|---|---|
| GET `/api/jobs/admin/dashboard` | `lib/admin.ts` → `adminApi.dashboard()` |
| GET `/api/jobs/admin/staff` | `lib/admin.ts` → `adminApi.listStaff()` |
| POST `/api/jobs/admin/staff` | `lib/admin.ts` → `adminApi.createStaff()` |
| PATCH `/api/jobs/admin/staff/{id}` | `lib/admin.ts` → `adminApi.updateStaff()` |
| POST `/api/jobs/admin/jobs` | `lib/admin.ts` → `adminApi.createJob()` |
| PATCH `/api/jobs/admin/{id}/reassign` | `lib/admin.ts` → `adminApi.reassign()` |
| PATCH `/api/jobs/admin/{id}/extend-sla` | `lib/admin.ts` → `adminApi.extendSla()` |
| PATCH `/api/jobs/admin/{id}/escalate` | `lib/admin.ts` → `adminApi.escalate()` |
| GET `/api/jobs/admin/job-types` 🆕 | `lib/jobTypes.ts` → `jobTypesApi.list()` |
| POST `/api/jobs/admin/job-types` 🆕 | `lib/jobTypes.ts` → `jobTypesApi.create()` |
| PATCH `/api/jobs/admin/job-types/{id}` 🆕 | `lib/jobTypes.ts` → `jobTypesApi.update()` |
| DELETE `/api/jobs/admin/job-types/{id}` 🆕 | `lib/jobTypes.ts` → `jobTypesApi.remove()` (soft-delete) |

### Realtime (SSE)

The single SSE stream below replaces the previous 30 s notification-bell poll
**and** drives live job-board / QA-queue / admin-dashboard refreshes. One
connection per browser tab; survives backgrounded tabs (openWhenHidden);
auto-reconnects with backoff. Uses `@microsoft/fetch-event-source` because
the browser's built-in `EventSource` can't carry the Bearer token.

| Endpoint | FE call site |
|---|---|
| GET `/api/jobs/events` 🆕 | `lib/sse.ts` (singleton `StudioEventStream`) + `hooks/useStudioEvent.ts` |

Subscribers (by event name):

| Event | Where it's handled |
|---|---|
| `notification.created` | `components/app/NotificationsBell` (bumps unread count) + `app/notifications/page.tsx` (silent refetch) |
| `job.created` / `job.claimed` | `app/jobs/page.tsx` (available) + `app/dashboard/page.tsx` (worker home) + admin dashboards |
| `job.started` / `job.submitted` | `app/qa/page.tsx` (queue) + admin dashboards |
| `job.approved` / `job.revision_requested` | `app/jobs/my-jobs/page.tsx` + `app/qa/page.tsx` + admin dashboards |
| `job.reassigned` / `job.sla_extended` / `job.escalated` | `app/jobs/my-jobs` + admin dashboards |
| `sla.tick` | `app/admin/page.tsx` (operations) — refetches the dashboard so SLA tone counts update without a page load |
| `hello` / `heartbeat` | Ignored at FE; just keep the connection alive |

### Performance, Notifications, Assets

| Endpoint | FE call site |
|---|---|
| GET `/api/jobs/performance/me` | `lib/performance.ts` → `performanceApi.me()` |
| GET `/api/jobs/performance/{staffId}` | `lib/performance.ts` → `performanceApi.forStaff()` |
| GET `/api/jobs/notifications` (incl. `?unread=true`) | `lib/notifications.ts` → `feed()` |
| PATCH `/api/jobs/notifications/{id}/read` | `lib/notifications.ts` → `markRead()` |
| PATCH `/api/jobs/notifications/read-all` | `lib/notifications.ts` → `markAllRead()` |
| POST `/api/jobs/{jobId}/assets` (multipart) | `lib/assets.ts` → `assetsApi.upload()` |
| GET `/api/jobs/{jobId}/assets` | `lib/assets.ts` → `assetsApi.list()` |
| DELETE `/api/jobs/{jobId}/assets/{assetId}` | `lib/assets.ts` → `assetsApi.remove()` |

### Studio client invariants

Mostly the same shape as conddo-app's, with these specific differences:

- **Refresh token in body, not cookie.** Studio's session uses `{accessToken,
  refreshToken}` returned in the response body; the FE stores both in
  `localStorage` via `lib/auth.ts`. The `/api/jobs/auth/refresh` endpoint
  accepts `{refreshToken}` in the body. Don't move this to cookies without
  warning — there's no cookie path on the Studio domain.
- **Authorization on `/api/jobs/auth/me`.** Unlike `/api/jobs/auth/{login,refresh,logout}`,
  the `/me` endpoint MUST carry the Bearer token — it's how the FE asks "who
  am I?". This is the one auth-namespaced endpoint that's authenticated. The
  FE handles this with an explicit `isPublicAuthCall` check (commit c9fb78d).
- **Same timeout invariant as conddo-app** (45 s JSON, 120 s upload).

### Pending — backend work that unblocks already-shipped FE

| Pending (backend) | FE state | Spec |
|---|---|---|
| **Website Builder API** (POST/PATCH/GET on `/jobs/:id/site/**`) | Builder route is a "coming soon" placeholder. AI section endpoints (`/ai-suggest`, `/palette`, `/rank-images`) are wired but no Section to write into yet. | `conddo_studio_combined.md` §21 — full design including V3 migration |
| **Job Export / Import** (`GET /jobs/:id/export`, `POST /jobs/:id/import`) | No FE yet; will add a download button + import drop zone on job detail once endpoints exist. | `conddo_studio_combined.md` §22 — full design including manifest.json schema |
| **Platform Admin — Cross-Tenant Management** (NEW: `GET/PATCH/DELETE /api/jobs/admin/platform/tenants/**` + `GET/PATCH/DELETE /api/jobs/admin/platform/users/**`) | Studio ADMIN today can only manage internal Handel Cores staff. We need to manage all platform tenants + users from the same Studio (you asked for this after a real-user signup post-mortem). | `conddo_studio_combined.md` §23 — full design with Phase 13a (read-only) + 13b (mutations) |

### Pending — FE work against already-shipped backend endpoints

| Pending (FE) | Backend ready? | Plan |
|---|---|---|
| Design Standards admin page (`/api/jobs/admin/design-standards/*`) | ✅ shipped in BACKEND_STATUS §2 | Mirror of the Job Types page — list/filter by kind+vertical, create/edit/soft-delete. Coming next slice. |
| Wire `staff_performance` to /performance/me display | ✅ shipped (daily recalc cron) | Page already calls `/performance/me`; just confirm the numbers look right end-to-end after the first 02:00 UTC recalc. |

### What's NOT in the gap, but was nearly missed

- `/api/jobs/intake` (service-to-service) is **intentionally not** called by
  the FE. It's called by `conddo-api` when a tenant creates a website change
  request. Keep it gated behind `X-Studio-Service-Token`.

---

## 4. Backend TODO — ranked by FE blocking

In the order that gives the most FE win per backend hour:

1. **Google Sign-in (conddo-api, `ACTION_LIST.md §1a`)** ⭐ unblocks a
   working "Continue with Google" on both /login and /onboarding/create-account.
   FE is fully wired (commit ddc2232) — buttons render + popup flow works, but
   POST /auth/google currently 404s. Estimated backend effort: small (1 schema
   column, 2 endpoints, ID-token verification using `google-api-client`).

2. **Platform Admin — read-only first, then mutations** (`conddo_studio_combined.md §23`)
   ⭐ requested after a real-user signup post-mortem. Studio ADMINs currently
   can only manage internal Handel Cores staff (`studio.staff` table) — they
   cannot see or modify tenants / tenant users. Two-phase delivery in the spec:
   - **Phase 13a (read-only):** `V4__studio_platform_admin.sql` audit table +
     `PlatformTenant` / `PlatformUser` mirror entities + GETs for tenants and
     users (global search + per-tenant). The Studio FE will add `/admin/platform/*`
     pages immediately after these land.
   - **Phase 13b (mutations):** PATCH/DELETE on tenants + users, suspend semantics,
     password reset, last-admin protection, refresh-token revocation, and new
     SSE events (`platform.tenant_status_changed`, `platform.user_deactivated`).

3. **Studio Builder API** (`conddo_studio_combined.md §21`) ⭐ unblocks the
   in-app website builder, currently an empty-state placeholder on the Studio.
   Schema migration + endpoint surface. The AI assistant should write directly
   into the new `site_sections.content` JSONB instead of the existing
   `jobs.ai_suggestions` field.

4. **Studio Export/Import** (`conddo_studio_combined.md §22`) ⭐ requested by
   ops staff so they can work locally. Two endpoints; needs a ZIP streamer
   and Cloudinary server-side download. Independent of #3.

5. **(In flight, already partially shipped)** SLA monitor — `phase1/studio-sla-monitor`
   worktree adds the `@Scheduled` walk that emits `sla.tick`. The Studio FE
   admin dashboard already subscribes to that event (commit 37cf235). No FE
   work needed when this lands.

---

## 5. Operational notes the backend team should know

These came up while debugging deployment issues — capturing them so the
backend team doesn't relearn them:

- **Signup → Studio job hand-off requires two env vars on `conddo-backend`.**
  `STUDIO_BASE_URL` + `STUDIO_SERVICE_TOKEN` must both be set or
  `HttpStudioJobGateway` silently no-ops on `TenantActivatedEvent` —
  signups complete but no `WEBSITE_BUILD` job appears in Studio. The
  exception is swallowed (correctly — we don't want a Studio outage to
  break signup) but there's no surfaced error in the platform response.
  Caught this in production after a real-user signup created the tenant
  but no Studio job; missing job needs a manual
  `POST /api/jobs/intake` with the same service token to recover.

- **First-signup user role is `TENANT_ADMIN`** — workspace admin, **not**
  platform admin. The role is scoped to that tenant only and RLS enforces
  it at the DB level. The Studio's internal staff (`studio.staff`) is a
  separate axis (`InternalRole`). Cross-tenant admin work is the new §23
  "Platform Admin" spec — not yet built.

- **Render free tier sleeps after ~15 min idle.** First request takes 30–60 s
  to wake the instance. The FE's 45 s timeout now surfaces this as a real
  error ("The server didn't respond in time"). If you upgrade to Render
  Starter ($7/mo), this goes away.
- **Render's bulk env import** ("Add from .env" / paste interface) accepts a
  standard `KEY=VALUE` file. We ship a template at
  `infra/conddo-studio.env.example` covering every var the Studio service
  needs, including the bootstrap admin seeder (`StudioAdminBootstrap.java`).
- **Trailing slashes in CORS origin env vars** are a real footgun. Browser
  `Origin` headers never include trailing slashes; Spring's `setAllowedOrigins`
  requires exact match. The env-file template now documents this.
- **CORS preflight cache.** Once a browser receives a 403 preflight, it
  caches that result for `Access-Control-Max-Age` (3600 s by default). After
  fixing CORS env vars, users need a hard refresh (Ctrl+Shift+R) to retry
  the preflight. Document this in any future deploy runbook.

---

## 6. Where to look when something breaks

If the FE complains and you don't know which endpoint, the fastest debug path:

1. **Browser DevTools → Network tab** — look for the failing request. The
   FE always sends `Origin`, `Authorization` (when applicable), and a JSON
   body. The response will include either the standard envelope or a CORS
   error / 401.
2. **The FE's `lib/api/*.ts`** — every endpoint has exactly one call site.
   Grep for the path and you'll find the consuming hook/component.
3. **The FE's `useApiQuery`** — most read endpoints flow through this hook
   (in `hooks/useApiQuery.ts` in both apps). It logs nothing on its own;
   wrap a screen in `<QueryBoundary>` (conddo-app only) for an inline error.

Backend issues that historically caused the FE to look broken:
- Stale cached CORS preflight → "Could not reach the server" + Network 403
- Render cold start → "The server didn't respond in time" after 45 s
- Spring `oauth2ResourceServer` validating a stale Bearer on a `permitAll`
  endpoint → "Authentication is required to access this resource"
- Frontend's `/auth/me` not getting the Bearer (FE bug, fixed in c9fb78d) →
  user lands in worker nav instead of their actual role
