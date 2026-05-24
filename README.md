# Conddo.io — Backend (Phase 0 / Core Platform)

Spring Boot 3 multi-module backend. Phase 0 establishes the spine everything
else hangs off: **multi-tenancy enforced by PostgreSQL Row Level Security**.

```
backend/                  (this repo — conddo-backend)
  pom.xml                 parent (multi-module)
  conddo-core/            Core Platform — tenancy, persistence, domain, services
  conddo-api/             Spring Boot app — REST API, config, Flyway migrations
  infra/                  Docker Compose dev stack (Postgres 16, Redis 7, MinIO)
```

## How tenant isolation works

1. Every tenant-scoped table has `tenant_id` and an RLS policy:
   `tenant_id = current_setting('app.tenant_id', true)::uuid`.
2. The app connects to Postgres as **`app_user`** — a *non-owner* role, so RLS
   is enforced and cannot be bypassed from application code.
3. Per request, after the bearer JWT is verified, the tenant is resolved from
   the token's `tenant_id` claim onto `TenantContext` (`JwtTenantContextFilter`).
   `SUPER_ADMIN` may scope to a chosen tenant with the `X-Act-As-Tenant` header.
4. Per transaction, `TenantSession.bind()` runs
   `SELECT set_config('app.tenant_id', '<uuid>', true)` so RLS scopes every
   query. The setting is transaction-local — it never leaks across the pool.
5. Migrations run as the **owner** role (`conddo_owner`); the app runs as
   `app_user`. Two roles, on purpose — owners bypass RLS.

If the tenant context is missing, `current_setting(..., true)` is NULL and
queries match **no rows** — isolation fails closed.

## Prerequisites

- JDK 17+
- The infra stack running: `cd infra && docker compose up -d` (Postgres/Redis/MinIO live in this repo)
- A dev RSA key pair for signing access tokens (gitignored; generate once):

  ```bash
  mkdir -p conddo-api/src/main/resources/keys
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
    -out conddo-api/src/main/resources/keys/dev_private.pem
  openssl rsa -in conddo-api/src/main/resources/keys/dev_private.pem \
    -pubout -out conddo-api/src/main/resources/keys/dev_public.pem
  ```

  In production set `CONDDO_JWT_PRIVATE_KEY` / `CONDDO_JWT_PUBLIC_KEY` to mounted key files.

## Run

```bash
# from the repo root
./mvnw spring-boot:run -pl conddo-api          # starts on :8080, Flyway migrates on boot
```

Build / test:

```bash
./mvnw clean verify                            # compile + run tests (tests need Docker)
```

## Try the isolation demo

Tenant signup also creates the tenant's first administrator; access is via a
short-lived JWT — the tenant comes from the token, not a header.

```bash
# Sign up two businesses, each with an admin account
curl -s -XPOST localhost:8080/api/v1/tenants -H 'Content-Type: application/json' \
  -d '{"name":"Amaka Styles","slug":"amaka-styles","verticalId":"fashion",
       "adminEmail":"amaka@example.com","adminPassword":"password123"}'
curl -s -XPOST localhost:8080/api/v1/tenants -H 'Content-Type: application/json' \
  -d '{"name":"Wellspring","slug":"wellspring","verticalId":"pharmacy",
       "adminEmail":"well@example.com","adminPassword":"password123"}'

# Log in as each admin and grab the access token
TA=$(curl -s -XPOST localhost:8080/auth/login -H 'Content-Type: application/json' \
  -d '{"tenantSlug":"amaka-styles","email":"amaka@example.com","password":"password123"}' | jq -r .data.accessToken)
TB=$(curl -s -XPOST localhost:8080/auth/login -H 'Content-Type: application/json' \
  -d '{"tenantSlug":"wellspring","email":"well@example.com","password":"password123"}' | jq -r .data.accessToken)

# Create a customer in each — no X-Tenant-Id; the tenant is in the JWT
curl -s -XPOST localhost:8080/api/v1/customers -H "Authorization: Bearer $TA" \
  -H 'Content-Type: application/json' -d '{"fullName":"Alice"}'
curl -s -XPOST localhost:8080/api/v1/customers -H "Authorization: Bearer $TB" \
  -H 'Content-Type: application/json' -d '{"fullName":"Bob"}'

# Each tenant sees ONLY its own customer — enforced by Postgres RLS
curl -s localhost:8080/api/v1/customers -H "Authorization: Bearer $TA"   # -> Alice
curl -s localhost:8080/api/v1/customers -H "Authorization: Bearer $TB"   # -> Bob
```

Health: `curl localhost:8080/actuator/health`

## What's next (Phase 1)

**Auth is done** — RSA-256 JWT access tokens, refresh-token rotation with reuse
detection, account lockout, password reset, and `SUPER_ADMIN` act-as
(PRD §6.2/§12.1). Remaining: subdomain → tenant resolution via Redis, the
audit-log writer, finishing RLS coverage on the credential tables, the
notifications engine (replacing the stub behind `NotificationPort`), the job
queue, and billing. See `ACTION_LIST.md`.
