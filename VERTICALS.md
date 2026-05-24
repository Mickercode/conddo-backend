# Conddo.io — Vertical → Tool Matrix

> **Source of truth for which capability tools each vertical activates, per plan tier.**
> Part of **Platform Architecture v1.0**. Supersedes the earlier 2-vertical
> (Fashion + Pharmacy) model. Each vertical is defined in a YAML module file
> (`modules/config/{vertical}.yml`, Architecture §13); the Module Registry resolves
> the tool list for a tenant's `vertical × plan` on `TenantCreated` (§5). Plan tiers
> are **cumulative**: Business includes all Starter tools; Pro includes all Business
> tools. The frontend renders nav/routes/widgets from each active tool's `UIManifest`
> (§16) — it does **not** hardcode this matrix.

## Plan tiers
`starter` → `business` (Starter + Business adds) → `pro` (Business + Pro adds).

## Matrix (as specified)

| Vertical | Starter tools | Business adds | Pro adds |
|---|---|---|---|
| **Pharmacy** | website, crm.pharmacy, inventory.pharmacy, pos.pharmacy, prescriptions, payments, analytics | staff, marketing.social, marketing.email, marketing.sms | marketing.ads, analytics.pharmacy |
| **Fashion / Tailoring** | website, crm, orders.fashion, payments, analytics | staff, marketing.social, marketing.email, marketing.sms, marketing.leads | marketing.ads |
| **Logistics** | website, crm, orders.logistics, payments, analytics | staff, marketing.social, marketing.sms | marketing.ads, tracking.advanced |
| **Retail / Shop** | website, crm, inventory.retail, pos, payments, analytics | staff, marketing.social, marketing.email, marketing.sms | marketing.ads, ecommerce |
| **Professional Services** | website, crm, bookings, payments, document-vault, analytics | staff, marketing.social, marketing.email, marketing.sms, marketing.leads | marketing.ads |
| **Food & Beverage** | website, crm, orders, payments, analytics | staff, marketing.social, marketing.email, marketing.sms, table-mgmt | marketing.ads |
| **Beauty & Wellness** | website, crm, bookings, payments, analytics | staff, marketing.social, marketing.email, marketing.sms, loyalty | marketing.ads |

> The original matrix used shorthand `social / email / sms / ads / leads`; these are
> the `marketing.*` tools below.

## Resolved tool list per tier (cumulative — what `resolveModules(vertical, plan)` returns)

**Pharmacy**
- `starter`: website, crm.pharmacy, inventory.pharmacy, pos.pharmacy, prescriptions, payments, analytics
- `business`: + staff, marketing.social, marketing.email, marketing.sms
- `pro`: + marketing.ads, analytics.pharmacy

**Fashion / Tailoring**
- `starter`: website, crm, orders.fashion, payments, analytics
- `business`: + staff, marketing.social, marketing.email, marketing.sms, marketing.leads
- `pro`: + marketing.ads

**Logistics**
- `starter`: website, crm, orders.logistics, payments, analytics
- `business`: + staff, marketing.social, marketing.sms
- `pro`: + marketing.ads, tracking.advanced

**Retail / Shop**
- `starter`: website, crm, inventory.retail, pos, payments, analytics
- `business`: + staff, marketing.social, marketing.email, marketing.sms
- `pro`: + marketing.ads, ecommerce

**Professional Services**
- `starter`: website, crm, bookings, payments, document-vault, analytics
- `business`: + staff, marketing.social, marketing.email, marketing.sms, marketing.leads
- `pro`: + marketing.ads

**Food & Beverage**
- `starter`: website, crm, orders, payments, analytics
- `business`: + staff, marketing.social, marketing.email, marketing.sms, table-mgmt
- `pro`: + marketing.ads

**Beauty & Wellness**
- `starter`: website, crm, bookings, payments, analytics
- `business`: + staff, marketing.social, marketing.email, marketing.sms, loyalty
- `pro`: + marketing.ads

## Tool ID legend

**Core (vertical-agnostic):** `website`, `crm`, `payments`, `analytics`, `bookings`,
`orders`, `inventory`, `pos`, `staff`.

**Marketing:** `marketing.social`, `marketing.email`, `marketing.sms`,
`marketing.ads`, `marketing.leads`.

**Vertical-specialized** (extend a core tool or add a vertical capability):
- `crm.pharmacy` — CRM + health profiles / allergy tracking
- `inventory.pharmacy` — inventory + expiry, NAFDAC, batch tracking
- `pos.pharmacy` — POS + prescription check, offline mode
- `prescriptions` — prescription records, refill reminders, interaction check
- `orders.fashion` — orders pipeline tuned for tailoring stages
- `orders.logistics` — orders/shipments pipeline for logistics
- `inventory.retail` — retail stock/SKU inventory
- `analytics.pharmacy` — pharmacy-specific analytics (Pro)
- `tracking.advanced` — advanced shipment tracking (Logistics Pro)
- `ecommerce` — online storefront/checkout (Retail Pro)
- `document-vault` — secure client document storage (Professional Services)
- `table-mgmt` — table/reservation management (Food & Beverage)
- `loyalty` — loyalty / rewards program (Beauty & Wellness)

> **Naming:** a vertical-specialized tool uses `{tool}.{vertical}` and (per
> Architecture §8) extends the generic tool's module so shared behaviour lives once.
> Generic `crm` / `orders` / `inventory` / `pos` are used where no specialization exists yet.

## Notes for implementers
- **Backend:** create one `modules/config/{vertical}.yml` per row (§13 schema). The
  `plans:` block lists the **resolved** (cumulative) tool list above. New verticals/tiers
  are config-only unless a genuinely new capability tool is needed (§P3).
- **Frontend:** never hardcode this list. The dashboard shell builds nav/routes/widgets
  from the active tools' `UIManifest`s returned for the tenant (§16). `GET /api/v1/verticals/{id}/config`
  already serves stage/measurement/section config per vertical.
