-- =====================================================================
-- Bootstrap the non-owner runtime role on a MANAGED Postgres (Render/RDS/…).
--
-- Our RLS model REQUIRES the application to connect as a role that does NOT own
-- the tables, so Row Level Security is enforced against it. Managed Postgres
-- gives you a single owner/admin role; this script creates the separate
-- `app_user`. (Locally, infra/postgres/init/01-app-user.sh does the same.)
--
-- Run it ONCE, as the owner/admin role, BEFORE the first app deploy — Flyway's
-- GRANTs to app_user (V2/V4/V6) require the role to already exist.
--
--   psql "<OWNER connection string>" \
--     -v app_password="$CONDDO_DB_APP_PASSWORD" \
--     -v dbname="$DATABASE_NAME" \
--     -f db/bootstrap/create-app-user.sql
--
-- If the role already exists this errors harmlessly — it is a one-time step.
-- =====================================================================

CREATE ROLE app_user WITH LOGIN PASSWORD :'app_password';
GRANT CONNECT ON DATABASE :"dbname" TO app_user;
GRANT USAGE  ON SCHEMA   public     TO app_user;
