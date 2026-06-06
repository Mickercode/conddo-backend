# Social Media + Creative Services — Cross-Team Spec

**Status**: Scoping (no code yet). This is sprint-2 work, not weekend launch.
**Touches**: conddo-app (FE), conddo-backend (BE auth + posting + payments
bridge), conddo-payments (charge orchestration), conddo-studio (creative
service jobs land here as Studio jobs), Conddo platform admin (creative
service catalog, monthly packages).

The big idea, in one paragraph: A Conddo tenant connects their social
accounts (Facebook Page, Instagram Business, LinkedIn Company, X, TikTok),
schedules posts to them from the dashboard, and — for any post — can
optionally request a paid creative service (graphic design, video edit,
ad creative) that gets routed to Conddo Studio as a job. They can also
subscribe to a monthly Brand Package that gives them a steady stream of
designed assets each month.

---

## 1. Vendor / API decisions (up front)

**RECOMMENDED**: Use **Ayrshare** (https://www.ayrshare.com) as a unified
API gateway. One integration, all platforms, no per-platform app review
wait. This was the original section's plan; we kept the native-integration
detail below as a fallback in case Ayrshare doesn't work out at scale.

### Option A (preferred) — Ayrshare API gateway

| Channel | Auth via Ayrshare | What we get |
|---|---|---|
| Facebook Page, Instagram Business, LinkedIn (personal + company), X, TikTok, YouTube, Pinterest, Reddit, GMB, Telegram, Bluesky, Threads | Each tenant becomes an **Ayrshare User Profile** ("sub-account") on our master Business/Enterprise account. The tenant connects each platform via Ayrshare's hosted connect UI (we redirect to it from our Connected Accounts page or embed it in an iframe). | A single POST endpoint (`POST https://api.ayrshare.com/api/post`) with `{post, platforms: [...], mediaUrls: [...], scheduleDate}` fans out to every selected platform. Ayrshare handles OAuth refresh, provider-specific quirks (IG Reels containers, X char limits, LinkedIn URN formats), media re-encoding, and rate limits. |

**Why this is a much better answer than native**:
- No Meta App Review wall-clock — Ayrshare already has approved app status,
  we ride on theirs. Go-live in **days**, not weeks.
- No LinkedIn Marketing Developer application required.
- No per-platform posting code in our backend — one HTTP call.
- Analytics + DM + comments + post history all in one API.
- Brand assets (logo URLs, captions saved as templates) supported.

**Cost** (Q2 2026 pricing — verify before committing):
- Business plan ~$149/month covers ~10 user profiles + unlimited posts.
- Enterprise scales to higher profile counts; per-tenant cost is the
  meaningful unit when we cross ~10 active social tenants.

**Per-tenant cost recovery**: bake the average ~$1-2/month/tenant Ayrshare
cost into the Growth plan's gross margin (Growth is already where
`social_scheduler` is gated). Launcher tenants don't get Ayrshare-backed
posting; their `media_library` works locally without it.

### Option B (fallback) — Native per-platform integrations

Kept here as a reference in case Ayrshare's terms / scale don't fit.
Skip this section if going Ayrshare-first.

| Channel | API | Auth | What we need |
|---|---|---|---|
| Facebook Page | Meta Graph API (`graph.facebook.com`) | OAuth2; page access token (long-lived ~60d) | Read pages, post photos/text/video, schedule via `scheduled_publish_time` |
| Instagram Business | Meta Graph API (IG Graph) | Same OAuth flow as FB; IG account linked to a FB Page | Container-then-publish flow for media |
| LinkedIn Company Page | LinkedIn Marketing Developer Platform | OAuth2 with `r_organization_social`, `w_organization_social` | Share posts on a Company Page (UGC API) |
| X (Twitter) | X API v2 | OAuth2 user context | Tweet, schedule (no native scheduling — we do it ourselves) |
| TikTok | TikTok Login Kit + TikTok Posts API | OAuth2 | Direct-post video |

**Phase 1 channels** (native path): Facebook + Instagram (one Meta app covers
both). LinkedIn next. X + TikTok in Phase 2.

**App registration prerequisites** (BLOCKING — native path only):
- Meta: register a Meta app, get `App ID` + `App Secret`, request App Review for
  `pages_manage_posts`, `instagram_basic`, `instagram_content_publish`,
  `pages_read_engagement`. **App Review takes 2-4 weeks.**
- LinkedIn: create a developer app, apply for the Marketing Developer Platform
  (separate access tier from regular OAuth). **Approval takes weeks.**
- All app secrets land in Render env (`META_APP_ID`, `META_APP_SECRET`,
  `LINKEDIN_CLIENT_ID`, `LINKEDIN_CLIENT_SECRET`).

### Env vars (Ayrshare path)

In Render (sync:false):
- `AYRSHARE_API_KEY` — master API key for our Business/Enterprise account
- `AYRSHARE_WEBHOOK_SECRET` — for incoming delivery / engagement webhooks
- `CONDDO_SOCIAL_TOKEN_KEY` (32-byte) — still used to encrypt the per-tenant
  Ayrshare profile key at rest (in `social_accounts.access_token`)

---

## 2. Connect flow — tenant attaches a social account

> **Ayrshare path adjustment to the schema below**: with Ayrshare, the
> shape is simpler — one row per tenant, not one row per (tenant,
> provider). Replace `social_accounts` with:
>
> ```sql
> CREATE TABLE tenant_social_profile (
>     tenant_id              UUID PRIMARY KEY REFERENCES tenants(id),
>     ayrshare_profile_key   TEXT NOT NULL,     -- encrypted at rest
>     ayrshare_user_id       VARCHAR(120),      -- profile id Ayrshare returns
>     connected_platforms    JSONB NOT NULL DEFAULT '[]',  -- ["facebook","instagram",...]
>     last_synced_at         TIMESTAMPTZ,
>     created_at, updated_at
> );
> ```
>
> Connect flow becomes: BE calls Ayrshare `POST /api/profiles` to create
> a profile if the tenant doesn't have one, stores the returned key, and
> returns the hosted connect URL (`https://app.ayrshare.com/social/{token}`).
> Tenant authorises each provider inside Ayrshare's hosted dialog. We poll
> Ayrshare's `/user` endpoint after redirect to refresh
> `connected_platforms`.
>
> The Option B native shape below remains as the fallback.



```
Tenant Settings → Connected Accounts → "Connect Facebook"
   ↓
Pop a Facebook OAuth dialog (FE redirects to graph.facebook.com/oauth/...
with our app ID, redirect URI = https://api.conddo.io/oauth/meta/callback,
state token tied to tenant_id + user_id, scopes as above)
   ↓
Meta redirects back with ?code=...&state=...
   ↓
BE exchanges code for a short-lived user token, then exchanges that for a
long-lived (~60-day) token, fetches the list of Pages the user manages,
fetches each page's page access token (long-lived) + the linked Instagram
Business Account id (if any)
   ↓
BE stores: { tenant_id, provider: 'facebook'|'instagram'|'linkedin',
external_id, name, access_token (encrypted), refresh_token (where
applicable), token_expires_at, scopes }
   ↓
Frontend re-renders Connected Accounts with the page name + a green
"Connected" pill + a "Disconnect" button.
```

### Schema

```sql
CREATE TABLE social_accounts (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            UUID NOT NULL REFERENCES tenants(id),
    provider             VARCHAR(20) NOT NULL,   -- 'facebook' | 'instagram' | 'linkedin' | 'x' | 'tiktok'
    external_id          VARCHAR(255) NOT NULL,  -- Page id / IG user id / LinkedIn URN
    external_name        VARCHAR(255),           -- "Seb&Bayor Pharmaceuticals" — for the UI
    access_token         TEXT NOT NULL,          -- encrypted at rest (pgcrypto / app-level AES-GCM)
    refresh_token        TEXT,
    token_expires_at     TIMESTAMPTZ,            -- triggers re-auth notice in the UI
    scopes               TEXT[],
    connected_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at           TIMESTAMPTZ,
    UNIQUE (tenant_id, provider, external_id)
);
```

Tokens are encrypted with an envelope key stored in Render secrets
(`CONDDO_SOCIAL_TOKEN_KEY`, 32 bytes). Rotate by re-encrypting on schedule;
the BE keeps a `key_version` byte prefix on each ciphertext.

### Token refresh + invalidation

- A nightly job (`@Scheduled`) checks `token_expires_at < now() + 7d` and refreshes
  where the provider supports it. Failures flip `revoked_at` and emit a
  notification to the tenant: "Reconnect Facebook to keep posting."
- Provider webhooks (where available) for explicit revocations also flip `revoked_at`.
- The post-scheduler refuses to send to any account where `revoked_at IS NOT NULL`.

---

## 3. Compose + schedule a post

The existing **marketing/social** page already has a SchedulePostModal stub.
This is the contract it needs:

### Schema

```sql
CREATE TABLE social_posts (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID NOT NULL REFERENCES tenants(id),
    author_user_id        UUID NOT NULL REFERENCES users(id),
    caption               TEXT NOT NULL,
    media                 JSONB,                  -- [{url, type:'image'|'video', width, height}, …]
    scheduled_at          TIMESTAMPTZ NOT NULL,
    timezone              VARCHAR(64) NOT NULL DEFAULT 'Africa/Lagos',
    status                VARCHAR(20) NOT NULL,   -- 'draft' | 'scheduled' | 'publishing' | 'published' | 'failed'
    created_at, updated_at
);

-- One row per target channel — a post can be cross-posted.
CREATE TABLE social_post_targets (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    post_id               UUID NOT NULL REFERENCES social_posts(id) ON DELETE CASCADE,
    social_account_id     UUID NOT NULL REFERENCES social_accounts(id),
    external_post_id      VARCHAR(255),           -- after successful publish
    status                VARCHAR(20) NOT NULL,   -- 'pending' | 'published' | 'failed'
    error_message         TEXT,
    published_at          TIMESTAMPTZ,
    UNIQUE (post_id, social_account_id)
);
```

### Endpoints

| Method | Path | Returns |
|---|---|---|
| GET    | `/api/v1/marketing/social/accounts` | `SocialAccount[]` |
| POST   | `/api/v1/marketing/social/accounts/{provider}/connect` | `{authUrl}` (OAuth redirect URL) |
| POST   | `/api/v1/marketing/social/accounts/{id}/disconnect` | 204 |
| GET    | `/api/v1/marketing/social/posts?status=&from=&to=` | `SocialPost[]` |
| POST   | `/api/v1/marketing/social/posts` | `SocialPost` (created) |
| PATCH  | `/api/v1/marketing/social/posts/{id}` | `SocialPost` (only `status=draft` posts are editable) |
| DELETE | `/api/v1/marketing/social/posts/{id}` | 204 (cancels schedule) |
| POST   | `/api/v1/marketing/social/posts/{id}/publish-now` | `SocialPost` |

### The publish job

A `@Scheduled` job runs every minute, picks up `social_posts` with
`status='scheduled' AND scheduled_at <= now()`, sets each one's status to
`publishing`, fans out to the provider APIs in parallel for each target,
and writes results back to `social_post_targets`. Failures: keep `social_post`
status as `scheduled` and retry up to 3 times on a 5-minute backoff before
flipping to `failed` and notifying the tenant.

---

## 4. Media library — uploads

Posts attach images / videos. Use the same Cloudinary integration the rest
of the platform uses (§9 in ACTION_LIST), with a new `media_library` table
scoped per tenant:

```sql
CREATE TABLE media_assets (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    cloudinary_id   VARCHAR(255) NOT NULL,
    url             TEXT NOT NULL,
    kind            VARCHAR(20) NOT NULL,    -- 'image' | 'video'
    width, height   INTEGER,
    bytes           BIGINT,
    uploaded_by     UUID REFERENCES users(id),
    created_at      TIMESTAMPTZ DEFAULT now()
);
```

Endpoints:
- `POST /api/v1/media` (multipart) → uploads to Cloudinary, writes the row
- `GET  /api/v1/media?kind=image&page=` → paginated library
- `DELETE /api/v1/media/{id}`

Per-tenant storage soft cap: 500 MB on Launcher, 5 GB on Growth, unlimited on
Scaler. Surface via a usage bar at the top of the media library.

---

## 5. Creative service — the "I need this designed" path

This is the new product. On the post composer, beside the **Schedule** CTA,
a **"Need creative help?"** chip opens a flow:

```
Step 1. Service type — Graphic design / Video edit / Ad creative
Step 2. Brief — what's the post for, tone, references, deadline
Step 3. Attach raw media (photos, footage) — uses the media library
Step 4. Price — surfaces from creative_service_offerings (BE-controlled
        catalog); the tenant sees a flat NGN price
Step 5. Pay — RoutePay checkout
Step 6. Done — a Studio job is created and the tenant gets a tracking link
```

### Schema

```sql
-- Catalog of services Conddo offers. Studio admin manages this.
CREATE TABLE creative_service_offerings (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code            VARCHAR(80) NOT NULL UNIQUE,   -- 'design_static', 'design_reels', 'ad_creative_static', …
    name            VARCHAR(160) NOT NULL,
    description     TEXT,
    price_kobo      INTEGER NOT NULL,              -- in kobo for precision
    turnaround_hours INTEGER NOT NULL,
    active          BOOLEAN NOT NULL DEFAULT true
);

-- A tenant's request for one of those services.
CREATE TABLE creative_service_requests (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    user_id         UUID NOT NULL REFERENCES users(id),
    offering_id     UUID NOT NULL REFERENCES creative_service_offerings(id),
    -- The post the creative is for (optional — they can also order standalone).
    social_post_id  UUID REFERENCES social_posts(id),
    brief           TEXT NOT NULL,
    attached_media  JSONB,            -- [media_asset_id, …]
    price_kobo      INTEGER NOT NULL, -- frozen at request time so catalog updates don't shift it
    status          VARCHAR(20) NOT NULL,
                    -- 'pending_payment' | 'queued' | 'in_progress' | 'delivered' | 'cancelled'
    payment_id      UUID,             -- references conddo-payments charge
    studio_job_id   UUID,             -- written once Studio creates the matching job
    delivery_media  JSONB,            -- [media_asset_id, …] returned by Studio
    delivered_at    TIMESTAMPTZ,
    created_at, updated_at
);
```

### Flow

1. FE → `POST /api/v1/creative-services/requests` with `{offering_code, brief,
   attached_media, social_post_id?}`. BE creates the row with
   `status='pending_payment'` and `price_kobo` frozen from the catalog.
2. BE responds with `{request, checkoutUrl}` (RoutePay-hosted page; same
   flow as the existing payments service).
3. FE redirects to checkout. RoutePay → conddo-payments webhook on
   success.
4. conddo-payments emits an internal event `creative.request.paid {requestId}`.
5. The creative-services service hands the request off to **Studio**:
   - `POST /api/jobs` (Studio backend) with a synthetic job whose title +
     description come from the request brief, the attached media URLs,
     and a `creativeServiceRequestId` foreign key.
   - On Studio's side, this surfaces as a normal job in the QA Queue,
     assignable to design / video-editor staff.
6. When Studio marks the job **delivered** (uploads final media + clicks
   Submit), Studio fires a webhook back to the main API:
   `POST /internal/creative-services/{id}/delivered` with the media URLs.
7. The creative-services service flips the request to `delivered`, attaches
   the final media to the original `social_posts` row (if linked), and
   notifies the tenant ("Your design is ready"). Tenant clicks → reviews →
   approves → original post auto-pulls the new media.

### Endpoints

| Method | Path | Notes |
|---|---|---|
| GET  | `/api/v1/creative-services/offerings` | Public catalog (auth required, but no plan gate — pricing visible to all tenants) |
| POST | `/api/v1/creative-services/requests` | Creates pending request + checkoutUrl |
| GET  | `/api/v1/creative-services/requests` | Tenant's own request history |
| GET  | `/api/v1/creative-services/requests/{id}` | Detail |
| POST | `/internal/creative-services/{id}/delivered` | Studio→main API webhook; service-token auth |

---

## 6. Monthly Brand Package — subscription on top of the base plan

A tenant subscribes to a recurring creative bundle that auto-includes N
designs / month, at a tier price.

```sql
CREATE TABLE brand_package_offerings (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code            VARCHAR(80) NOT NULL UNIQUE,   -- 'starter_brand', 'pro_brand', …
    name            VARCHAR(160) NOT NULL,
    monthly_price_kobo  INTEGER NOT NULL,
    includes        JSONB NOT NULL,    -- {design_static: 8, design_reels: 2, ad_creative_static: 4}
    active          BOOLEAN NOT NULL DEFAULT true
);

CREATE TABLE brand_package_subscriptions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES tenants(id),
    offering_id         UUID NOT NULL REFERENCES brand_package_offerings(id),
    status              VARCHAR(20) NOT NULL,    -- 'active' | 'past_due' | 'cancelled'
    current_period_start TIMESTAMPTZ NOT NULL,
    current_period_end   TIMESTAMPTZ NOT NULL,
    cancelled_at        TIMESTAMPTZ,
    created_at, updated_at
);

-- Tracks consumption against the period's quota.
CREATE TABLE brand_package_usage (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_id     UUID NOT NULL REFERENCES brand_package_subscriptions(id),
    period_start        TIMESTAMPTZ NOT NULL,
    period_end          TIMESTAMPTZ NOT NULL,
    -- One row per period start; counts increment as requests come in.
    counts              JSONB NOT NULL DEFAULT '{}'  -- {design_static: 3, …}
);
```

Active subscribers can create creative service requests **without paying
per-job** as long as they have quota left. The request's `payment_id`
stays null and `price_kobo = 0`; instead the BE increments
`brand_package_usage.counts[offering_code]` and rejects (HTTP 409
`QUOTA_EXHAUSTED`) when the count would exceed the package's `includes`.

Renewal: a `@Scheduled` job at start-of-day rolls the period and charges
the subscription via RoutePay's card-on-file (extends the existing
[BILLING_TIERS_SPEC.md](./BILLING_TIERS_SPEC.md) renewal infrastructure).
Failed charges flip the subscription to `past_due` and queue a notification.

### Endpoints

| Method | Path |
|---|---|
| GET  | `/api/v1/brand-packages/offerings` |
| GET  | `/api/v1/brand-packages/subscription` (the tenant's current) |
| POST | `/api/v1/brand-packages/subscription` `{offering_code}` — initial charge |
| POST | `/api/v1/brand-packages/subscription/cancel` |

---

## 7. FE work (conddo-app)

Pages / components to build, in roughly this order:

| Order | Surface | What |
|---|---|---|
| 1 | `/settings/connections` (restore from "Coming soon") | List of providers, "Connect" buttons that hit the BE's `connect` endpoint and redirect to the OAuth dialog, per-account "Disconnect" with a confirm modal |
| 2 | `/marketing/social` composer | Multi-account target selector (chips), media picker from library + upload, date/time picker, schedule button. Already partially scaffolded in [SchedulePostModal](../conddo-app/components/app/SchedulePostModal.tsx). |
| 3 | `/marketing/media` (new) | Grid view of `media_assets`, upload CTA, delete, usage bar |
| 4 | `RequestCreativeModal` | Brief textarea + attached-media picker + offering selector + price preview + "Pay & queue" CTA. Opens from the composer or `/marketing/social` row "+ Add creative" action |
| 5 | `/marketing/creative-services` (new) | Tenant's request history with delivery status + download links + brand-package summary card |
| 6 | `/marketing/brand-packages` (new) | Catalog of monthly packages with "Subscribe" CTA |
| 7 | Dashboard widget | "X designs left this month" card for Brand Package subscribers |

## 8. Studio FE work

| Surface | What |
|---|---|
| Job detail | New "Creative service request" sidebar block when `job.creativeServiceRequestId` is set — surfaces the brief, raw media downloads, and the offering code. |
| Job submit | On delivery, the existing submit flow auto-fires the `internal/creative-services/{id}/delivered` webhook with the uploaded final media — no Studio UI change needed beyond the existing asset upload. |
| Admin → Job Types | Add `CREATIVE_DESIGN`, `CREATIVE_VIDEO`, `CREATIVE_AD` types so dispatchers can route by skill. |

---

## 9. Plan gating

| Feature | Launcher | Growth | Scaler |
|---|---|---|---|
| `social_scheduler` (connect + post + schedule) | — | ✓ | ✓ |
| `media_library` | basic (500MB) | ✓ (5GB) | ✓ (unlimited) |
| `creative_services_marketplace` | ✓ (pay-per-job) | ✓ (pay-per-job) | ✓ (pay-per-job) |
| `brand_package_subscription` | — | ✓ | ✓ |

Pay-per-job creative services are deliberately on Launcher too — this is
revenue we want from the smallest tenants. The subscription model is a
Growth+ feature because the package only makes economic sense when paired
with an active marketing surface.

---

## 10. Open product questions

- Provider mix beyond Phase 1 (FB+IG) — confirm priority of LinkedIn vs X vs TikTok.
- Brand Package tier prices + included counts — needs product/finance call before catalog seed.
- Ad management (Meta ads budget top-up) — is that a separate Phase or rolled into creative-ad-creative requests? Today the FE has an empty "Ads" tab.
- Studio team capacity model — at 50 active brand-package subscribers each pulling ~10 designs/month that's 500 jobs/month against today's Studio team. Capacity planning before launch.

---

## 11. Out of scope (call out so nobody builds it by accident)

- DM / messenger replies (totally different API set; ChatOps surface, not marketing)
- Reels analytics / engagement scoring (Meta provides this — defer until v2)
- Cross-tenant content templates (multi-tenant data sharing — explicit no)
- AI image generation (separate vendor decision)
- White-glove community management (not a product, that's a services contract)

---

## 12. Phasing

### Ayrshare path (recommended, ~1 week per phase)

**Phase 1 (this sprint, ~1 week)**: Sign up for Ayrshare Business, store
the master `AYRSHARE_API_KEY`. Schema lands (`social_accounts`,
`social_posts`, `social_post_targets`). Ship the connect endpoint
(creates an Ayrshare User Profile per tenant, returns the hosted
connect URL). Ship the schedule endpoint (proxies to Ayrshare's
`/api/post`). FE Connected Accounts page already shipped — the BE
flips the provider `status` from `pending_approval` → `live` and the
Connect button starts opening the Ayrshare hosted dialog.

**Phase 2 (~1 week)**: Media library + creative service requests live.
Scheduling + publishing covers FB + IG + LinkedIn + X + TikTok + YouTube
+ Pinterest + GMB all at once (Ayrshare covers them as one API).

**Phase 3 (~2 weeks)**: Brand Package subscriptions. Dashboard widgets.
Studio admin tools for creative offerings.

**Phase 4**: Analytics rollup (Ayrshare provides post-level analytics —
we just aggregate + present).

### Native path (fallback only)

**Phase 1**: Meta app registration starts (~3-week App Review clock),
schema lands, connect/disconnect endpoints work in dev. FE buttons
stay disabled with "Awaiting Meta approval".

**Phase 2 (3-4 wks)**: FB + IG live after App Review.

**Phase 3 (6-8 wks)**: LinkedIn (separate Marketing Developer Platform
approval). Brand Packages.

**Phase 4**: X, TikTok, ads, analytics.

The 2-4 week App Review delay is the **only blocker on the critical path**
of the native path. If we go native, submit Meta + LinkedIn apps the day
the BE schema lands so the wall-clock starts ticking.
