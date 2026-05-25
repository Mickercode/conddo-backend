# Conddo.io — Platform Architecture Document

> **Version 1.0 · Handel Cores · 2026 · Confidential**
> Authoritative technical specification. Build strictly according to this document.
> **Status of this file:** backend-repo copy of the full spec — **§1–20 now captured**.
> The **canonical master is `conddo_architecture.md` at the workspace root**; keep this copy
> in sync with it. Where this v1.0 spec and the older `ACTION_LIST.md` §11 REST contract
> disagree, **this document wins**; the live backend currently implements an earlier subset
> (see `conddo-api-integration` notes / `ACTION_LIST.md`). Vertical→tool matrix: `VERTICALS.md`.

| Field | Detail |
|---|---|
| System | Conddo.io — Multi-tenant SaaS Business Operating System |
| Backend | Java 21 + Spring Boot 3.x |
| Frontend | React + Next.js 14 |
| Database | PostgreSQL 16 (self-hosted) |
| Cache | Redis 7 |
| Storage | MinIO (self-hosted, S3-compatible) |
| Auth | Custom JWT — Spring Security 6 |
| Messaging | Redis Pub/Sub (Event Bus) |
| Queue | BullMQ on Redis |
| Deployment | Docker + Docker Compose + Nginx + VPS |

## Contents
1. System Overview · 2. Architecture Principles · 3. High-Level Diagram · 4. Auth Service ·
5. Module Registry · 6. Module Factory · 7. Capability Tool System · 8. Base Module ·
9. Event Bus · 10. API Gateway · 11. Multi-Tenancy & Data Isolation · 12. Database Architecture ·
13. Module Definition (YAML) · 14. Standard Capability Tools Catalogue ·
15. Vertical Module Configurations · 16. Frontend Module Manifest System ·
17. Maven Project Structure · 18. Infrastructure & Deployment · 19. Security Architecture ·
20. Implementation Sequence

---

## 1. System Overview
Multi-tenant, vertically-aware SaaS giving Nigerian SMEs a complete digital operating
system in one subscription. Verticals — pharmacy, fashion, logistics, retail, professional
services, etc. — each get a different configuration of tools activated on signup.

Three core principles: **Configuration over code** (verticals in YAML, not Java) ·
**Inheritance over duplication** (modules extend a common base) · **Events over coupling**
(modules talk via a shared event bus, never direct calls).

**Key decisions:** Java 21 + Spring Boot 3 backend · Custom JWT + Spring Security ·
module pattern = inheritance from abstract `BaseModule` · activation = YAML-driven factory ·
tenant isolation = PostgreSQL RLS + session variable · inter-module comms = Redis Pub/Sub ·
self-hosted Postgres + MinIO · **notifications: Resend (email/OTP) + Brevo (SMS)** —
free tiers (revised from the original single-Brevo plan; see ACTION_LIST §11.14).

## 2. Architecture Principles
- **P1 — Tenant First.** Every table has `tenant_id`; every request is tenant-scoped; isolation enforced at the DB via RLS, not just the app.
- **P2 — Modules Are Independent Units.** No module imports another. Only shared dep is `conddo-core` (BaseModule, events, shared types). They communicate only through the event bus.
- **P3 — Configuration Drives Verticals.** A new vertical = a YAML file, no Java. Java only when a genuinely new capability tool is needed.
- **P4 — Events Are the Integration Layer.** A module publishes an event; it never calls another module's service directly.
- **P5 — Fail Safely.** Degrade gracefully — POS works offline, core ops complete if the bus is down, a failed notification never rolls back a transaction. Non-critical ops are async/queued.
- **P6 — Security Is Not Optional.** Three layers must all agree: JWT claims at the gateway, Spring Security method annotations at the service, PostgreSQL RLS at the DB.
- **P7 — The Registry Owns Module Lifecycle.** Nothing activates/deactivates/upgrades itself; everything goes through the Module Registry.

## 3. High-Level Architecture
```
Clients (React/Next.js Web+PWA)
   │ HTTPS
Nginx — SSL termination · subdomain routing (businessname.conddo.io → tenant)
   │
API Gateway — JWT validation · tenant context injection · module access check · rate limiting (Redis) · routing
   ├── Auth Service — signup/signin/OTP/JWT/tenant creation/refresh/password reset
   │        │ publishes TenantCreated
   └── Module Registry — lifecycle · activation/deactivation · plan upgrades · YAML loader · Module Factory · Tool Registry
            │ activates
   ────────────────── EVENT BUS (Redis Pub/Sub) ──────────────────
     Core Modules         Vertical Modules     Marketing Engine     Analytics Module
     (Website, CRM,       (Pharmacy, Fashion,  (Social, Ads,        (Revenue, Products,
      Payments, Bookings,  Logistics, Retail…)  Email, SMS,          Customers, Marketing)
      Orders, Inventory,                        Leads, Referral)
      Staff)
            │
   PostgreSQL (self-hosted, RLS per tenant)
   ├── Redis  — sessions · job queue · event bus · rate limiting · cache
   └── MinIO  — file storage · tenant assets · documents · images
```

> **Service topology — see `SERVICE_TOPOLOGY.md`.** The boxes above sit on three
> planes: **Control** (`conddo-api` dashboard), **Production** (`conddo-studio` — the
> jobs engine for website/ads/design/copy), and **Runtime** (`conddo-sites`, the
> not-yet-built service that *serves* live tenant sites on subdomains + custom
> domains). Split services along runtime/security boundaries, not features.

## 4. Auth Service
Standalone Spring Boot app. **The only service that creates tenants.** Publishes
`TenantCreated`, which the Module Registry consumes to activate modules.
Responsibilities: OTP phone-verified registration (via Brevo), authentication + JWT issuance,
refresh-token management, password reset, tenant creation, domain events.
**Not** responsible for authorisation, module access, or business logic.

### 4.2 Data Models
```sql
-- TENANTS
CREATE TABLE tenants (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                    TEXT NOT NULL,
    slug                    TEXT UNIQUE NOT NULL,   -- businessname.conddo.io
    vertical                TEXT NOT NULL,          -- pharmacy, fashion, etc.
    plan                    TEXT NOT NULL,          -- starter, business, pro
    status                  TEXT DEFAULT 'onboarding',
    custom_domain           TEXT,
    paystack_customer_id    TEXT,
    paystack_subscription_id TEXT,
    metadata                JSONB DEFAULT '{}',
    created_at              TIMESTAMPTZ DEFAULT NOW(),
    updated_at              TIMESTAMPTZ DEFAULT NOW()
);
-- USERS  (role: SUPER_ADMIN | TENANT_ADMIN | STAFF | CUSTOMER | REP; password_hash = BCrypt cost 12)
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE,
    email TEXT UNIQUE, phone TEXT UNIQUE NOT NULL, password_hash TEXT NOT NULL,
    full_name TEXT NOT NULL, role TEXT NOT NULL,
    is_active BOOLEAN DEFAULT true, is_verified BOOLEAN DEFAULT false,
    last_login_at TIMESTAMPTZ, created_at TIMESTAMPTZ DEFAULT NOW()
);
-- REFRESH TOKENS  (token_hash BCrypt; family_id for rotation-reuse detection)
CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    token_hash TEXT UNIQUE NOT NULL, family_id UUID NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL, revoked_at TIMESTAMPTZ, created_at TIMESTAMPTZ DEFAULT NOW()
);
-- OTP CODES  (purpose: SIGNUP | PASSWORD_RESET | LOGIN)
CREATE TABLE otp_codes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    phone TEXT NOT NULL, code_hash TEXT NOT NULL, purpose TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL, used_at TIMESTAMPTZ, attempts INTEGER DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
```

### 4.3 API Endpoints
| Method | Endpoint | Description |
|---|---|---|
| POST | `/auth/signup/initiate` | Submit phone, send OTP via Brevo |
| POST | `/auth/signup/verify-otp` | Verify OTP, return temp token |
| POST | `/auth/signup/complete` | Submit business profile → create tenant + user, issue JWT |
| POST | `/auth/login` | Email/phone + password → JWT pair |
| POST | `/auth/refresh` | Refresh access token from refresh cookie |
| POST | `/auth/logout` | Revoke refresh token |
| POST | `/auth/password/forgot` | Send OTP for password reset |
| POST | `/auth/password/reset` | Verify OTP + set new password |
| GET | `/auth/me` | Current user profile from JWT |

### 4.4 JWT Structure
Access token, **15-min expiry**, RSA-256. Payload carries: `sub` (user id), `tenantId`,
`role`, **`vertical`**, **`plan`**, and **`activeModules`** (array of active tool ids, e.g.
`["website","crm.pharmacy","inventory.pharmacy","pos.pharmacy","prescriptions","payments","marketing.social","analytics"]`),
plus `iat`/`exp`.
Refresh token: opaque string, BCrypt-hashed in DB, **30-day**, httpOnly + Secure + SameSite=Strict
cookie, **rotation** on every use, **family tracking** (a reused token invalidates the whole family).

### 4.5 Signup Flow
`POST /signup/initiate {phone}` → Brevo sends OTP → `200 {otpSent:true}`.
`POST /signup/verify-otp {phone, code}` → `200 {token:'tmp'}`.
`POST /signup/complete {businessName, vertical, plan, …}` → create Tenant + User, issue JWT pair,
**publish `TenantCreated`** → `201 {accessToken, user, tenant}`.

### 4.6 Spring Security
`@EnableWebSecurity` + `@EnableMethodSecurity`; CSRF disabled; **stateless** sessions;
`/auth/**` and `/health` permitAll, everything else authenticated; a `JwtAuthFilter`
(before `UsernamePasswordAuthenticationFilter`) validates the token, sets the tenant context
(for RLS) and the SecurityContext, then clears the tenant context after the request.

## 5. Module Registry Service
Central authority for all module lifecycle. Responsibilities: listen for `TenantCreated` →
activate; resolve active modules for a tenant's `vertical × plan`; delegate assembly to the
Module Factory; handle plan upgrade/downgrade and module suspend/reactivate; serve module
manifests to the frontend; keep the JWT's `activeModules` current (via Auth Service callback).

### 5.2 Data Models
```sql
-- AVAILABLE TOOLS (classpath scan at startup)
CREATE TABLE available_tools (
    tool_id TEXT PRIMARY KEY, display_name TEXT NOT NULL, version TEXT NOT NULL,
    category TEXT NOT NULL, description TEXT, is_active BOOLEAN DEFAULT true,
    registered_at TIMESTAMPTZ DEFAULT NOW()
);
-- MODULE DEFINITIONS (from YAML files)
CREATE TABLE module_definitions (
    module_id TEXT PRIMARY KEY, display_name TEXT NOT NULL, vertical TEXT,  -- null = all
    min_plan TEXT NOT NULL, config_yaml TEXT NOT NULL, version TEXT NOT NULL,
    is_active BOOLEAN DEFAULT true, loaded_at TIMESTAMPTZ DEFAULT NOW()
);
-- TENANT MODULE ACTIVATIONS (status: ACTIVE | SUSPENDED | DEACTIVATED)
CREATE TABLE tenant_modules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    module_id TEXT NOT NULL REFERENCES module_definitions(module_id),
    status TEXT NOT NULL DEFAULT 'ACTIVE', config_override JSONB DEFAULT '{}',
    activated_at TIMESTAMPTZ DEFAULT NOW(), deactivated_at TIMESTAMPTZ,
    UNIQUE (tenant_id, module_id)
);
-- TENANT TOOL PROVISIONING
CREATE TABLE tenant_tools (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL, module_id TEXT NOT NULL, tool_id TEXT NOT NULL,
    config JSONB NOT NULL, provisioned_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE (tenant_id, module_id, tool_id)
);
```

### 5.3–5.4 Activation Flow & Service
On `TenantCreated {tenantId, vertical, plan}`: (1) load vertical YAML, (2) resolve module list
for the plan tier, (3) `moduleFactory.assemble(moduleId, tenantId)` per module (resolve tools,
provision schema, seed defaults), (4) INSERT `tenant_modules`, (5) publish `TenantActivated
{tenantId, activeModules}`. `onPlanUpgrade` assembles+activates only the newly-unlocked modules
then publishes `ModulesUpgraded`. `isModuleActive(tenantId, moduleId)` backs the gateway's access
check. `resolveModules(vertical, plan)` = `configLoader.load(vertical).getModulesForPlan(plan)`.

## 6. Module Factory
Assembly engine: reads a module's YAML, resolves + configures each capability tool for the
tenant, provisions DB schema, returns a `ComposedModule`. Steps in `assemble(moduleId, tenantId)`:
(1) load YAML definition, (2) for each tool: `toolRegistry.get(id)` then `tool.configure(mergeConfig(defConfig, tenantOverrides))`,
(3) `schemaManager.provision(tool, tenantId)`, (4) `tool.seedDefaults(tenantId)`,
(5) save `TenantTool` rows, (6) `tool.onActivate(tenantId)`.
**SchemaManager** runs each tool's migrations for the tenant (tables already carry RLS policies;
`tenant_id` injected at runtime) and sets tenant context via `SET LOCAL app.tenant_id = '<id>'`.

## 7. Capability Tool System
A **Capability Tool** is the fundamental building block — a discrete, self-contained capability
that owns its schema, endpoints, events, and UI description.

### 7.1 `CapabilityTool` interface
```java
public interface CapabilityTool {
    // identity
    String getToolId(); String getDisplayName(); String getVersion();
    String getCategory();  // DATA | ACTION | NOTIFICATION | MARKETING | REPORTING | COMPLIANCE
    // configuration
    CapabilityTool configure(ToolConfig config); ToolConfig getConfig();
    // database
    List<Migration> getMigrations(); void seedDefaults(String tenantId);
    // api
    List<ApiEndpoint> getEndpoints();
    // events
    List<String> publishedEvents(); List<String> consumedEvents();
    // ui manifest — tells the React frontend what to render
    UIManifest getUIManifest();
    // lifecycle
    void onActivate(String tenantId); void onDeactivate(String tenantId);
    void onUpgrade(String tenantId, String fromVersion); HealthStatus healthCheck(String tenantId);
}
```

### 7.2 `UIManifest` — Frontend Configuration
**The frontend never hardcodes module UI.** Each tool returns a manifest the React app renders.
```java
public class UIManifest {
    private String toolId;
    private NavItem navItem;                 // sidebar nav entry {label, icon, path, order}
    private List<Route> routes;              // {path, component}
    private List<Permission> permissions;
    private List<Widget> dashboardWidgets;   // {component, position}
    private Map<String,Object> config;       // tool-specific UI config
}
```
Example (`inventory.pharmacy`): navItem `{Inventory, package, /inventory, order:4}`; routes for
`/inventory`, `/inventory/add`, `/inventory/import`, `/inventory/:id`, `/inventory/expiry`;
dashboard widgets `LowStockAlert` (metric) + `ExpiryAlert` (sidebar); config `enableExpiryTracking`,
`enableNAFDACField`, `enableBatchTracking`, `defaultCategories`.

### 7.3 `AbstractCapabilityTool`
Base class implementing sensible defaults (no widgets, no seed, healthy) + helpers `publish(tenantId, event)`
(stamps tenantId + sourceTool) and `withTenant(tenantId, runnable)` (sets `SET LOCAL app.tenant_id`).

## 8. Base Module (abstract)
Parent for all modules; never instantiated directly. Spring injects shared infra: `EventBus`,
`NotificationService`, `StorageService`, `AuditLogger`, `TenantContextHolder`. Abstract: `getModuleId`,
`getManifest`, `onActivate`, `onDeactivate`, `healthCheck`. Shared behaviour: `publishEvent` (stamps
tenantId/sourceModule/timestamp), `sendNotification`, `audit` (writes audit_log with user from context),
`assertTenantAccess(resourceTenantId)` (throws `TenantAccessViolationException` on mismatch).
**Inheritance example:** `InventoryModule extends BaseModule` (addProduct → save + publish `ProductAdded`
+ audit); `PharmacyInventoryModule extends InventoryModule` (overrides id `inventory.pharmacy`,
`onActivate` seeds pharmacy categories + schedules expiry checks; adds `checkExpiryAlerts`, drug
`checkInteraction`). Vertical specializations extend the generic module so shared behaviour lives once.

## 9. Event Bus
**Modules NEVER call each other directly — non-negotiable.** All inter-module comms go through the bus.
`EventBus.publish(event)` → Redis `convertAndSend` on channel `conddo.{tenantId}.{eventType}`
(e.g. `conddo.abc-123.ORDER_COMPLETED`). `DomainEvent` base: `eventId`, `tenantId`, `sourceModule`,
`timestamp`, `version`, abstract `getEventType()`.

### 9.2 Domain Events Catalogue
| Event | Published by | Consumed by |
|---|---|---|
| TenantCreated | Auth Service | Module Registry (activation) |
| TenantActivated | Module Registry | Auth Service (JWT), Website queue |
| PlanUpgraded | Payments | Module Registry (activate new modules) |
| SaleCompleted | POS | Inventory (decrement), Analytics, CRM (history), Marketing (post-purchase) |
| OrderStageChanged | Orders | Notifications (SMS/email), Analytics |
| ProductLowStock | Inventory | Notifications (owner), Dashboard widget |
| ProductExpiringSoon | Inventory | Notifications (owner), Expiry tracker |
| PrescriptionDispensed | Prescriptions | Compliance log, CRM (refill schedule) |
| BookingConfirmed | Bookings | Notifications (SMS), Analytics |
| BookingReminder | Job Queue (scheduled) | Notifications (SMS) |
| PaymentReceived | Payments | Orders/Bookings (mark paid), Analytics, CRM |
| RefillDue | Job Queue (scheduled) | Notifications (SMS to patient) |
| PostScheduled | Social | Job Queue (publish) |
| AdCampaignStarted | Ads | Analytics (start tracking) |
| CustomerCreated | CRM | Marketing (welcome sequence) |

Listeners subscribe by channel pattern, e.g. `@RedisListener(channel='conddo.*.SALE_COMPLETED')`;
multiple modules (Inventory, Analytics) listen to the same event independently.

## 10. API Gateway
Single entry point. Responsibilities: SSL termination (Nginx), JWT validation (signature/expiry/issuer),
tenant context injection (`app.tenant_id` for RLS), **module access check** (is the route's module in
`JWT.activeModules`? else 403), rate limiting (Redis per tenant+endpoint), routing, audit logging,
CORS (explicit whitelist, no wildcard). No business logic.
**Filter chain:** Nginx (SSL, subdomain → `X-Tenant-Slug`) → CORS → Rate Limit (429) → JWT (validate,
extract tenantId/userId/role/activeModules, set SecurityContext + ThreadLocal) → Module Access (403) →
Audit → Controller.
**Routing:** `/api/v1/{module}/{resource}`. The `{module}` segment maps to the access check —
e.g. `/api/v1/prescriptions/**` requires `prescriptions`; `/api/v1/pos/**` requires `pos` or `pos.pharmacy`.
Examples: `/api/v1/inventory/products`, `/api/v1/pos/sales`, `/api/v1/crm/customers`,
`/api/v1/marketing/social/posts`, `/api/v1/analytics/dashboard`.

## 11. Multi-Tenancy & Data Isolation
Enforced at BOTH app layer (JWT + filter) AND DB layer (RLS); both must agree.
**Flow:** JWT → `JwtAuthFilter` extracts `tenantId` → `TenantContextHolder` (ThreadLocal) → JPA
interceptor runs `SET LOCAL app.tenant_id = '<id>'` before every query → RLS policy enforces →
context cleared after request.
**RLS pattern (every table, every module):**
```sql
ALTER TABLE {table} ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON {table}
    USING (tenant_id = current_setting('app.tenant_id')::UUID);
CREATE POLICY super_admin_bypass ON {table}
    USING (current_setting('app.role') = 'SUPER_ADMIN');
```
**Subdomain routing:** Nginx `server_name ~^(?<slug>.+)\.conddo\.io$` injects `X-Tenant-Slug`;
gateway resolves slug→tenantId via Redis cache (`tenant:slug:{slug}`, 5-min TTL, refreshed on tenant update).

## 12. Database Architecture
**Core platform tables:** `audit_log` (tenant_id, user_id, module, action, resource_type/id,
before_state/after_state JSONB, ip, user_agent, created_at), `event_store` (replay log: tenant_id,
event_type, source_module, payload JSONB, published_at), `job_queue` (tenant_id, job_type, payload,
status PENDING…, attempts/max_attempts, scheduled_for, completed_at, error).
**Naming:** tables `module_resource` snake_case plural (`inventory_products`, `crm_customers`);
columns snake_case; PK `id` UUID default `gen_random_uuid()`; FKs `{singular}_id`; timestamps always
`TIMESTAMPTZ`; soft delete `deleted_at` nullable; status = TEXT + CHECK; JSON = `JSONB`; indexes `idx_{table}_{cols}`.
**Indexes:** minimum per table `idx_{t}_tenant_id`, `idx_{t}_created_at (DESC)`, composite
`idx_{t}_tenant_created (tenant_id, created_at DESC)`; plus per query pattern (e.g.
`idx_inventory_products_expiry` partial WHERE expiry_date IS NOT NULL; `idx_crm_customers_phone`;
`idx_event_store_type`).

## 13. Module Definition — YAML Schema
Every vertical module is one YAML file. Schema (all required unless optional):
```yaml
# modules/config/{moduleId}.yml
moduleId: string          # e.g. 'pharmacy'
displayName: string
icon: string              # lucide-react icon name
description: string       # shown in vertical selection grid
tools:                    # tools to activate for this vertical
  - id: string            # CapabilityTool.getToolId()
    tier: string          # starter | business | pro
    config: { key: value }   # tool-specific (optional)
plans:                    # resolved tool list per tier (cumulative)
  starter:  { tools: [ids] }
  business: { tools: [ids] }   # includes all starter
  pro:      { tools: [ids] }   # includes all business
website:                  # website template config
  template: string
  sections: [section-type-id]
  colorPalette: string    # health | fashion | neutral …
onboarding:               # checklist
  steps:
    - { title, description, tool, action }   # action = frontend route
seo:
  keywords: [string]
  metaDescriptionTemplate: string
```
**Example `pharmacy.yml`** (abridged): tools `website`(health-trust/health), `crm.pharmacy`
(healthProfiles, allergyTracking), `inventory.pharmacy` (expiry, NAFDAC, batch, default drug
categories), `pos.pharmacy` (prescriptionCheck, offlineMode, quickPicks), `prescriptions`
(refillReminders, interactionCheck), `payments`, `analytics` — all starter; `staff`,
`marketing.{social,email,sms}` — business; `marketing.ads` — pro. `plans.{starter,business,pro}`
list the cumulative resolved tool ids (see `VERTICALS.md` for all 7 verticals). Website sections:
`hero-trust, services-grid, why-choose-us, location-hours, contact-whatsapp`. Onboarding steps:
add products → set up payments → add staff → review website.

---

## 14. Standard Capability Tools Catalogue
All pre-built tools in the Tool Registry; each is a Spring Boot component implementing
`CapabilityTool`. New tools are added here only when YAML config alone can't cover a new
capability. (Full per-tool catalogue maintained as tools are built; tool IDs + categories per
`VERTICALS.md` and §7.)

## 15. Vertical Module Configurations
Summary of tool activation per vertical × plan tier; full YAML definitions are separate files
per vertical (`conddo-registry/.../config/modules/{vertical}.yml`). **The authoritative matrix is
`VERTICALS.md`** (7 verticals: pharmacy, fashion, logistics, retail, professional-services,
food-and-beverage, beauty-and-wellness).

## 16. Frontend Module Manifest System
The React frontend **never hardcodes** module nav/routes/features — all of it comes from module
manifests served by the registry, so new modules appear in the UI with zero frontend code changes.

### 16.1 How the frontend uses manifests
On login, after the JWT is received:
1. Decode JWT → `{ activeModules, vertical, plan }`.
2. `GET /api/v1/registry/manifests?modules=<activeModules>` → array of `UIManifest`.
3. Build nav = `manifests.flatMap(m => m.navItems).sort((a,b) => a.order - b.order)`.
4. Register routes = `manifests.flatMap(m => m.routes)`.
5. Render: sidebar shows navItems, router resolves routes, dashboard shows widgets — all from
   manifests. Result: a Pharmacy gets Home/Inventory/POS/Prescriptions/Customers/Payments/
   Marketing/Analytics/Staff; a Fashion business gets Home/Orders/Customers/Payments/Marketing/
   Analytics/Staff — same React app, zero hardcoding.

### 16.2 Dashboard widget zones
Tools contribute widgets to predefined zones: `metric` (top metric cards), `chart` (main charts),
`list` (recent-activity lists), `sidebar` (right-column alerts/summaries), `alert` (top banners).
Pharmacy → metric: RevenueToday, LowStockCount, ExpiringCount, PendingPrescriptions; list:
RecentSales; sidebar: ExpiryAlert, LowStockAlert. Fashion → metric: RevenueToday, PendingOrders,
NewCustomers, OutstandingPayments; list: RecentOrders; sidebar: UpcomingFittings.
(Frontend consumption notes: `conddo-app/ARCHITECTURE-FRONTEND.md`.)

## 17. Maven Project Structure
Parent `conddo-platform/` (parent POM) with modules:
- `conddo-gateway/` — API Gateway (filters: `JwtAuthFilter`, `TenantContextFilter`,
  `ModuleAccessFilter`, `RateLimitFilter`).
- `conddo-auth/` — Auth Service (controllers, `AuthService`/`JwtService`/`OtpService`/
  `TenantCreationService`, models, `TenantCreatedEvent`+publisher, Flyway `V1..V4`).
- `conddo-core/` — shared: `module/` (`BaseModule`, `CapabilityTool`, `AbstractCapabilityTool`,
  `ComposedModule`, `ModuleManifest`, `UIManifest`), `event/` (`DomainEvent`, `EventBus`, event types),
  `tenant/` (`TenantContextHolder`, `TenantAccessViolationException`), `notification/`
  (`NotificationService`, `BrevoClient`), `storage/` (`StorageService`), `audit/` (`AuditLogger`).
- `conddo-registry/` — Module Registry (`ModuleRegistryService`, `ModuleFactory`, `ModuleConfigLoader`,
  `SchemaManager`, `ToolRegistry`; models; `config/modules/*.yml`).
- `conddo-modules/` — one module per capability, vertical specializations nested:
  `module-website`, `module-crm` (+`module-crm-pharmacy`), `module-inventory`
  (+`module-inventory-pharmacy`, `module-inventory-retail`), `module-pos` (+`module-pos-pharmacy`),
  `module-orders` (+`module-orders-fashion`, `module-orders-logistics`), `module-bookings`,
  `module-prescriptions`, `module-payments`, `module-marketing`
  (+`module-marketing-{social,email,sms,ads}`), `module-analytics`, `module-staff`.

## 18. Infrastructure & Deployment
**Docker Compose** services: `nginx` (80/443, SSL + subdomain), `gateway`, `auth-service`,
`registry-service`, one service per module (`module-inventory`, …), `postgres:16`, `redis:7-alpine`
(password), `minio`. Volumes: `postgres_data`, `redis_data`, `minio_data`.
**Env (.env, never committed):** `DB_USER/DB_PASSWORD/DATABASE_URL`; `JWT_PRIVATE_KEY`/`JWT_PUBLIC_KEY`
(RSA-256 pair); `REDIS_PASSWORD/REDIS_URL`; `MINIO_USER/PASSWORD/URL/BUCKET`; `BREVO_API_KEY`/
`BREVO_SENDER_EMAIL`/`BREVO_SENDER_NAME`; `PAYSTACK_SECRET_KEY`/`PUBLIC_KEY`/`WEBHOOK_SECRET`;
`CLAUDE_API_KEY`; `BASE_DOMAIN=conddo.io`, `API_BASE_URL`, `CORS_ALLOWED_ORIGINS`.

## 19. Security Architecture
All security controls must be active in **development too**, not just production (prod security
bugs are far costlier). Three-layer authorisation (gateway JWT claims · Spring Security method
annotations · PostgreSQL RLS) must all agree — see §6/§10/§11.

## 20. Implementation Sequence
Build in strict phase order; each phase depends on the previous; don't build module features
before infra is stable.

### Implementation Rules for the Agent (mandatory)
- Always check section numbers against this doc — don't work from memory.
- Every new table: `tenant_id` + RLS enabled + index on `tenant_id` **before** any other work.
- Every module extends `BaseModule`; every tool implements `CapabilityTool` — no exceptions.
- No module imports another module's classes — **events only**.
- No hardcoded tenant IDs — always from `TenantContextHolder`.
- No DB query without tenant context set — always via the filter chain.
- Every endpoint has a matching `@PreAuthorize`. Every mutation writes to `audit_log`.
- All secrets via env vars — never in committed code/config.
- Every service exposes `/health` → 200 when healthy.
- Flyway migrations for every schema change — no JPA auto-ddl in prod.
- All inter-module comms via `EventBus.publish()` — no cross-module service calls.
- YAML module configs are the source of truth for verticals — never hardcode vertical logic in Java.
