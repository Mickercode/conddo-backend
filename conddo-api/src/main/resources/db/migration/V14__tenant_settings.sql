-- =====================================================================
-- V14 — Tenant settings (§11.11): business profile, branding, social, location,
-- business hours, and notification preferences. These are tenant-level config
-- (like the booking config in V12), so they live on the tenants row. Industry
-- (vertical_id) and subdomain (slug) stay read-only via the API.
-- =====================================================================

ALTER TABLE tenants
    ADD COLUMN tagline           TEXT,
    ADD COLUMN description       TEXT,
    ADD COLUMN contact_email     TEXT,
    ADD COLUMN contact_phone     TEXT,
    ADD COLUMN primary_color     TEXT,
    ADD COLUMN logo_url          TEXT,
    ADD COLUMN social_handles    JSONB,
    ADD COLUMN location          JSONB,
    ADD COLUMN business_hours    JSONB,
    ADD COLUMN notification_prefs JSONB;
