-- Runs once on Testcontainers Postgres init (as the owner role, conddo_owner).
-- Mirrors infra/postgres/init/01-app-user.sh: creates the non-owner runtime
-- role so RLS is enforced against the application exactly as in production.
CREATE ROLE app_user WITH LOGIN PASSWORD 'app_password';
GRANT CONNECT ON DATABASE conddo TO app_user;
GRANT USAGE ON SCHEMA public TO app_user;
