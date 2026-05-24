# Deploying the Conddo.io API (Render)

The API (Spring Boot, a long-running JVM server) deploys to **Render**. The
frontend (Next.js) deploys separately to **Vercel** — Vercel can't run a JVM
server, so the two live on different hosts and talk over HTTPS.

> **Why this needs a runbook:** our multi-tenant isolation depends on the app
> connecting to Postgres as a **non-owner role** while Flyway migrates as the
> **owner** (the two-role RLS model). Managed Postgres only gives you one role,
> so we create the second one by hand. Get this wrong and RLS is bypassed.

---

## 0. Prerequisites
- This repo on GitHub (already: `origin → github.com/Mickercode/conddo-backend`).
- A Render account; (recommended) the `conddo.io` domain for custom subdomains.
- `openssl` and `psql` locally.

## 1. Generate production JWT keys (once)
Do **not** reuse the gitignored dev keys. Generate a prod pair locally; you'll
upload them to Render as Secret Files (never commit them):
```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out jwt_private.pem
openssl rsa -in jwt_private.pem -pubout -out jwt_public.pem
```

## 2. Push the deploy-ready code
```bash
git checkout main && git merge phase1/auth      # or deploy the phase1/auth branch
git push origin main
```

## 3. Create the managed Postgres (Render dashboard → New → Postgres)
- Postgres 16, region **Frankfurt** (closest to Nigeria).
- After it's created, note from the database page: **host, port, database name,
  username, password** (this username is the **owner/admin** role).

## 4. Bootstrap the non-owner `app_user` (once, before first deploy)
Flyway's grants target `app_user`, so it must exist first. Using the database's
**external** connection string (owner credentials):
```bash
psql "<owner external connection string>" \
  -v app_password="<choose-a-strong-password>" \
  -v dbname="<database name>" \
  -f db/bootstrap/create-app-user.sql
```
Keep `<choose-a-strong-password>` — it's `CONDDO_DB_APP_PASSWORD` below.

## 5. Create the Web Service (Render → New → Web Service, from this repo)
- Runtime **Docker** (uses the repo `Dockerfile`); Health Check Path
  **`/actuator/health`**.
- **Secret Files:** upload `jwt_private.pem` and `jwt_public.pem` (they mount at
  `/etc/secrets/...`).
- **Environment variables:**

  | Key | Value |
  |---|---|
  | `CONDDO_DB_URL` | `jdbc:postgresql://<host>:<port>/<dbname>` (use the **internal** host) |
  | `CONDDO_DB_OWNER` | the DB's owner username |
  | `CONDDO_DB_OWNER_PASSWORD` | the DB's owner password |
  | `CONDDO_DB_APP_USER` | `app_user` |
  | `CONDDO_DB_APP_PASSWORD` | the password from step 4 |
  | `CONDDO_JWT_PRIVATE_KEY` | `file:/etc/secrets/jwt_private.pem` |
  | `CONDDO_JWT_PUBLIC_KEY` | `file:/etc/secrets/jwt_public.pem` |
  | `CONDDO_JWT_ISSUER` | `https://api.conddo.io` (or your API URL) |
  | `CONDDO_CORS_ALLOWED_ORIGINS` | `https://app.conddo.io` (your frontend origin; comma-separate multiples) |
  | `CONDDO_AUTH_COOKIE_SECURE` | `true` |
  | `CONDDO_AUTH_COOKIE_SAMESITE` | `Strict` (same-site) or `None` (cross-site — see §7) |
  | `CONDDO_REDIS_HEALTH_ENABLED` | `false` (until Redis is added in Phase 1 item 2) |

On deploy, the image builds, Flyway migrates **as the owner**, and the app
connects **as `app_user`** — RLS enforced.

## 6. Verify the live API
```bash
API=https://<your-service>.onrender.com
curl -s $API/actuator/health                       # {"status":"UP"}
curl -s -XPOST $API/api/v1/tenants -H 'Content-Type: application/json' \
  -d '{"name":"Acme","slug":"acme","adminEmail":"a@acme.test","adminPassword":"password123"}'
curl -s -XPOST $API/auth/login -H 'Content-Type: application/json' \
  -d '{"tenantSlug":"acme","email":"a@acme.test","password":"password123"}'   # -> accessToken
```

## 7. Domains, CORS & the refresh cookie (important for the frontend)
The refresh token is an httpOnly cookie. Whether the browser sends it depends on
how the two apps are domained:

- **Recommended — same registrable domain.** Point `api.conddo.io` → Render and
  `app.conddo.io` → Vercel (both support custom domains). The cookie is then
  *first-party*: set `CONDDO_AUTH_COOKIE_SAMESITE=Strict`. No third-party-cookie
  problems.
- **Cross-site** (frontend on `*.vercel.app`, API on `*.onrender.com`). Set
  `CONDDO_AUTH_COOKIE_SAMESITE=None` (with `CONDDO_AUTH_COOKIE_SECURE=true`).
  Works, but browsers' third-party-cookie restrictions may block it — prefer the
  custom-domain option for anything beyond early testing.

Set `CONDDO_CORS_ALLOWED_ORIGINS` to the exact frontend origin(s).

## 8. Frontend integration notes
- **Access token:** returned in the login/refresh response body; send it as
  `Authorization: Bearer <token>` on `/api/v1/*` calls.
- **Refresh/logout:** call `/auth/refresh` and `/auth/logout` with
  `fetch(..., { credentials: 'include' })` so the cookie is sent.
- CORS is preconfigured to allow `Authorization`, `Content-Type`, and
  `X-Act-As-Tenant` with credentials.

## 9. Ongoing
- **Migrations** run automatically on each deploy (Flyway, as owner). Add new
  `V{n}__*.sql` files; never edit applied ones.
- **Redis** (Phase 1 item 2): provision Render Key Value, set `CONDDO_REDIS_HOST`
  /`CONDDO_REDIS_PORT`, and flip `CONDDO_REDIS_HEALTH_ENABLED=true`.
- **Object storage:** swap MinIO for S3/Cloudflare R2 (`CONDDO_MINIO_*`) when
  uploads land.
