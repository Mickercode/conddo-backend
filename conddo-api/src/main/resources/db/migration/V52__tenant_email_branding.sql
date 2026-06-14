-- =====================================================================
-- V52 — Per-tenant email branding.
--
-- Today every outbound email goes out as "Conddo <noreply@conddo.io>" —
-- customer-confusing on order confirmations / booking notifications.
-- Two new columns let a tenant override the display name (defaults to
-- the business name) and the reply-to header (defaults to the contact
-- email).
--
-- Brevo's per-message sender + replyTo do this without DNS/DKIM work —
-- emails still come from the verified Conddo address, but the recipient
-- sees the tenant's brand.
-- =====================================================================

ALTER TABLE tenants
    ADD COLUMN email_from_name VARCHAR(150),
    ADD COLUMN email_reply_to  VARCHAR(254);
