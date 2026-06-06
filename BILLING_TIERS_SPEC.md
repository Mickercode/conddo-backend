# Billing Tiers — Backend Spec

**Status**: FE shipped 2026-06-05 (pricing page, onboarding, settings/billing).
BE work blocks plan switching, real renewal, and feature gating.

**Source of truth for plan content**:
[`conddo-pricing-tiers.md`](../conddo-pricing-tiers.md) (product doc).

**FE contract**:
[`conddo-app/lib/api/subscriptions.ts`](../conddo-app/lib/api/subscriptions.ts) —
the wire shapes below match it exactly.

The FE assumes endpoints exist and `QueryBoundary` degrades any 5xx into
"Billing is being set up". When the BE deploys this work, no FE change is
needed — the existing pages light up.

---

## Naming change — `Tenant.planId`

The existing matrix uses `"starter" | "business" | "pro"`. The new product
names are `"launcher" | "growth" | "scaler"`. Treat this as a **rename**:

- New signups land with `planId IN ('launcher', 'growth')`.
- Existing test/dev tenants on `"starter" / "business" / "pro"` get a
  one-shot data migration mapping:
  - `starter` → `launcher`
  - `business` → `growth`
  - `pro` → `scaler`
- `VerticalToolMatrix` keys keep the **old** names internally (the matrix is
  tier-cumulative — the names there are tiers, not product names). Add a
  `planName → tier` translation at the JWT-mint and manifest-resolve boundaries
  so all the existing matrix logic keeps working without changes.

Acceptable shape:
```java
String tierForPlan(String planId) {
  return switch (planId) {
    case "launcher" -> "starter";
    case "growth"   -> "business";
    case "scaler"   -> "pro";
    default         -> "starter";  // safe default
  };
}
```

---

## Phase 1 — ship before paid users land

Phase 1 is the minimum to take real money. Everything in Phase 2 can ship
after if necessary.

### 1. Schema

```sql
-- The plan catalog. Seed once with the three rows below.
CREATE TABLE subscription_plans (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(50) NOT NULL UNIQUE,  -- 'launcher' | 'growth' | 'scaler'
    display_name    VARCHAR(50) NOT NULL,
    monthly_price   INTEGER,                       -- Kobo; NULL for Scaler
    quarterly_price INTEGER,                       -- Kobo; NULL for Scaler
    is_custom       BOOLEAN NOT NULL DEFAULT false,
    is_active       BOOLEAN NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ DEFAULT now()
);

-- One row per tenant subscription. There's exactly one "active" row per
-- tenant at any time (others are historical, status='cancelled' or 'expired').
CREATE TABLE tenant_subscriptions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    plan_id         UUID NOT NULL REFERENCES subscription_plans(id),
    billing_cycle   VARCHAR(20) NOT NULL,          -- 'monthly' | 'quarterly' | 'custom'
    status          VARCHAR(20) NOT NULL,          -- 'trialing' | 'active' | 'grace' | 'expired' | 'cancelled'
    amount_paid     INTEGER NOT NULL DEFAULT 0,    -- Kobo
    started_at      TIMESTAMPTZ NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    trial_ends_at   TIMESTAMPTZ,                   -- NULL after trial
    cancelled_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ DEFAULT now(),
    updated_at      TIMESTAMPTZ DEFAULT now()
);
CREATE UNIQUE INDEX idx_tenant_active_sub
  ON tenant_subscriptions (tenant_id)
  WHERE status IN ('trialing', 'active', 'grace');

-- Feature gates per plan. Single source of truth for what each plan unlocks.
CREATE TABLE plan_features (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id         UUID NOT NULL REFERENCES subscription_plans(id),
    feature_key     VARCHAR(100) NOT NULL,
    feature_value   VARCHAR(255),                  -- 'true'/'false', integer, or 'unlimited'
    UNIQUE (plan_id, feature_key)
);
```

`tenant_subscriptions` is tenant-scoped but does **not** need RLS — billing
state is read by the JWT-mint path and the Plan service, both of which run
in a privileged role. (Same pattern as `tenants` itself.) Apply RLS later if
we expose self-service billing history.

### 2. Seed the catalog

Idempotent seeder on startup (or a Flyway migration with `ON CONFLICT DO
NOTHING`):

```
launcher  ₦20,000/mo  ₦54,000/qtr  is_custom=false
growth    ₦45,000/mo  ₦120,000/qtr is_custom=false
scaler    ₦120,000/mo NULL         is_custom=true
```

Feature matrix (each row → `plan_features` row per plan).

> **⚠ Spec change 2026-06-05**: `order_management` and `bookings` moved from
> Growth to Launcher (was `false → true` on the Launcher column). Reason in
> the product doc:
> [conddo-pricing-tiers.md](../conddo-pricing-tiers.md). Action for BE: flip
> those two rows in `plan_features` for the Launcher plan (data update; no
> schema or controller change). The `@RequiresFeature("order_management")`
> and `@RequiresFeature("bookings")` annotations stay in place; only the
> plan_features rows change.
>
> Also new: `public_bookings_widget` (Growth+) — used to gate the
> customer-facing self-book widget on the tenant's public site. Internal
> booking management itself is Launcher.

| feature_key             | launcher    | growth      | scaler      |
|-------------------------|-------------|-------------|-------------|
| website                 | true        | true        | true        |
| **order_management**    | **true**    | true        | true        |
| **bookings**            | **true**    | true        | true        |
| custom_domain           | false       | true        | true        |
| business_email          | false       | true        | true        |
| public_bookings_widget  | false       | true        | true        |
| email_campaigns         | false       | true        | true        |
| sms_campaigns           | false       | true        | true        |
| social_scheduler        | false       | true        | true        |
| ad_management           | false       | true        | true        |
| multi_location          | false       | false       | true        |
| api_access              | false       | false       | true        |
| advanced_analytics      | false       | false       | true        |
| staff_accounts          | "2"         | "5"         | "unlimited" |

### 3. Trial on signup

When `POST /auth/register/complete` creates a new tenant:

1. Look up the plan by name (from the wizard's `planId` — `launcher` or
   `growth`; **Scaler is sales-led, never reaches this path**).
2. Insert a `tenant_subscriptions` row:
   - `status = 'trialing'`
   - `billing_cycle = 'monthly'` (default; tenant can change at upgrade)
   - `started_at = now()`
   - `trial_ends_at = now() + interval '14 days'`
   - `expires_at = trial_ends_at` (during trial, expiry = trial end)
   - `amount_paid = 0`
3. Mint the JWT with `planId` + `activeModules` as usual.

If the wizard's `planId` is missing or unknown → default to `launcher`.

### 4. Endpoints

All require auth; `TENANT_ADMIN` only unless noted.

| Method | Path | Auth | Body | Returns | Notes |
|---|---|---|---|---|---|
| GET | `/api/v1/billing/plans` | optional | – | `Plan[]` | Public catalog — also used by the marketing pricing page server-side later |
| GET | `/api/v1/billing/subscription` | required | – | `Subscription` | Tenant's active subscription |
| POST | `/api/v1/billing/upgrade` | TENANT_ADMIN | `{planId, billingCycle}` | `Subscription` | Prorated; takes effect immediately for upgrades, end-of-period for downgrades |
| POST | `/api/v1/billing/cancel` | TENANT_ADMIN | – | `Subscription` | Soft cancel — stamps `cancelled_at`; access continues until `expires_at` then status flips to `cancelled` |

**Wire shapes — must match exactly** (FE types in
[`subscriptions.ts`](../conddo-app/lib/api/subscriptions.ts)):

```json
// Plan
{
  "id": "growth",
  "displayName": "Growth",
  "monthlyPrice": 45000,
  "quarterlyPrice": 120000,
  "isCustom": false,
  "features": {
    "website": "true",
    "custom_domain": "true",
    "order_management": "true",
    "ad_management": "true",
    "staff_accounts": "5"
  }
}

// Subscription
{
  "planId": "growth",
  "planDisplayName": "Growth",
  "billingCycle": "monthly",
  "status": "trialing",
  "amountPaid": 0,
  "startedAt": "2026-06-05T10:00:00Z",
  "expiresAt": "2026-06-19T10:00:00Z",
  "trialEndsAt": "2026-06-19T10:00:00Z",
  "cancelledAt": null,
  "daysRemaining": 14
}
```

Prices are sent in **Naira** (not Kobo) — convert in the response builder.
The DB stores Kobo for precision; the wire uses whole-Naira integers.

### 5. Feature gating

Tenant requests for gated modules must check `plan_features`. Pattern:

```java
public boolean hasFeature(UUID tenantId, String featureKey) {
    TenantSubscription sub = subscriptionRepository.findActiveByTenantId(tenantId);
    if (sub == null) return false;
    if (sub.getStatus().equals("expired") || sub.getStatus().equals("cancelled")
        && sub.getExpiresAt().isBefore(Instant.now())) return false;
    PlanFeature f = planFeatureRepository.findByPlanIdAndKey(sub.getPlanId(), featureKey);
    return f != null && "true".equalsIgnoreCase(f.getFeatureValue());
}
```

Apply at the controller layer with a custom annotation `@RequiresFeature("ad_management")`
that an aspect resolves before the method runs. On a miss, return **403**
with the body the FE expects (`PlanGate` renders directly from this):

```json
{
  "error": "PLAN_UPGRADE_REQUIRED",
  "message": "Ad management is available on the Growth plan.",
  "upgrade_url": "https://app.conddo.io/settings/billing",
  "requiredPlan": "Growth",
  "requiredPlanPrice": 45000
}
```

Modules to annotate in Phase 1:
- `marketing.ads` controller → `@RequiresFeature("ad_management")`
- `marketing.email` → `@RequiresFeature("email_campaigns")`
- `marketing.sms` → `@RequiresFeature("sms_campaigns")`
- `marketing.social` → `@RequiresFeature("social_scheduler")`
- `orders` → `@RequiresFeature("order_management")` (Launcher gets no orders)
- `bookings` → `@RequiresFeature("bookings")`
- (Staff invite → check `staff_accounts` limit numerically before creating
  the row.)

### 6. Renewal & non-payment

In Phase 1 this can be **manual / on-demand** — no cron required:

- When `now() > expires_at` and status is `trialing` or `active`, the next
  authenticated request flips the row to `grace` (suggested 3-day grace
  period; configurable via `conddo.billing.grace-period-days`).
- After grace, status flips to `expired`; the tenant is read-only (see §7).
- The JWT mint path checks the subscription on every login; the manifest
  resolve path checks on every request that hits it.

In Phase 2, a nightly cron does the same work proactively + fires SMS/email
reminders (see Phase 2 §A).

### 7. Read-only / expired state

When `status = 'expired'` OR `status = 'grace'`:

- All write endpoints (POST/PATCH/DELETE) return 402 Payment Required with:
  ```json
  {
    "error": "SUBSCRIPTION_EXPIRED",
    "message": "Your subscription has expired. Renew to start writing again.",
    "upgrade_url": "https://app.conddo.io/settings/billing"
  }
  ```
- Read endpoints continue to work (so the tenant can pull their data out).
- The website (`tenant.website_status`) flips to `OFFLINE` — Studio webhook
  removes from public DNS.

### 8. Tests

- Trial expires → status flips to grace on next request, then to expired
  after grace days.
- `hasFeature` returns false on expired sub.
- Annotated endpoint → 403 PLAN_UPGRADE_REQUIRED for Launcher hitting an
  ad endpoint.
- `staff_accounts` limit blocks the 3rd Launcher invite, 6th Growth invite.
- Upgrade endpoint inserts new row + marks old `cancelled` atomically.
- Public catalog `/billing/plans` returns the full feature map for all three
  plans (smoke test for the seeder).

---

## Phase 2 — automation, after launch

### A. Renewal reminders + scheduled state transitions

```
@Scheduled(cron = "0 0 8 * * *", zone = "Africa/Lagos")  // 8am daily
public void runBillingTransitions() {
  // 7 days before expires_at → SMS + email "Your plan renews on {date}"
  // 3 days before               → SMS + email
  // Day-of                      → SMS + email + flip to 'grace' if no payment
  // After grace_period_days     → flip to 'expired', mark website OFFLINE
}
```

Reuse `SmsSender` + `EmailSender`. Idempotency: a
`subscription_notification_log` table or a `last_reminded_at` column on
`tenant_subscriptions`.

### B. Trial nudges

- **Day 10**: SMS + email "4 days left in your trial — add billing details"
- **Day 14 (= trial_ends_at - 1 minute)**: SMS + email "Trial ends in 1 hour"
- **Day 14 + grace_period_days**: SMS + email "Workspace going read-only"

Same cron as §A.

### C. Payment gateway integration

The payment side is on RoutePay (already in flight — see
[ACTION_LIST.md §7a](./ACTION_LIST.md#7a-payments-conddo-payments-standalone-web-service)).
The Billing module calls `conddo-payments` to:
- Init a subscription charge at upgrade-time → returns a hosted-checkout URL
- Verify on callback → on success, write the new `tenant_subscriptions` row
  and flip status to `active`
- Renewal: card-on-file charge via RoutePay tokenization; failure → SMS the
  tenant + flip to `grace`

The FE's `/upgrade` flow:
1. POST `/billing/upgrade {planId, billingCycle}` →
2. BE returns `{checkoutUrl}` (not the final Subscription yet, because
   payment is async)
3. FE redirects to the hosted RoutePay page
4. On callback / FE polls verify → BE finalizes the upgrade
5. FE refetches `/billing/subscription` and re-renders

Treat this as an extension of the existing RoutePay flow — same gateway,
same sub-account model.

### D. Domain + business email provisioning (Growth plan)

When a tenant upgrades to Growth (or signs up directly on Growth and
trial ends with payment): trigger the 9stacks integration to:
- Provision a `.com.ng` domain for the tenant's chosen handle
- Create N business email mailboxes (default: 1 — `info@{handle}.com.ng`)
- Wire those into the tenant's website DNS via Studio

This is currently a manual step. The 9stacks API availability is an
**open item** in the product spec — confirm before building.

### E. Invoices

Tenants should be able to download paid invoices from `/settings/billing` →
"Billing history" table. Out of scope for Phase 1; render a "Billing
history coming soon" placeholder below the plan card.

---

## Open product questions (referenced in the spec)

- [ ] Payment gateway provider — assumed RoutePay (consistent with §7a).
- [ ] 9stacks API availability for automated domain + business email
      provisioning on Growth upgrade.
- [ ] Grace period length — Phase 1 default to **3 days** (configurable).
- [ ] Quarterly billing at signup, or only after first monthly payment? Phase
      1 default: **available at signup**; the wizard records `billing_cycle`
      on the registration payload.

---

## Why this shape

- **Three tables (plans / subscriptions / features), not one giant tenant
  column**: lets the catalog change without touching every tenant row, and
  lets us run different prices/features for grandfathered customers without
  schema changes.
- **Soft-cancel via `cancelled_at` + read-only on `expired`**: don't delete
  data when a tenant stops paying — preserve for re-activation. Spec calls
  this out explicitly.
- **`@RequiresFeature` annotation, not in-method checks**: gating becomes
  a one-line decoration; auditable in one place; easy to grep for `multi_location`
  to confirm coverage.
- **Phase 1 is manual renewal, Phase 2 is the cron**: we can take real
  money before the cron lands. The cron is a polish layer, not a gate.

---

## Out of scope

- Per-seat pricing (Scaler may use it later; product call needed)
- Add-ons (extra mailboxes, extra ad-account spend caps)
- Annual billing (only Monthly + Quarterly per spec)
- Refunds (handled manually via support)
