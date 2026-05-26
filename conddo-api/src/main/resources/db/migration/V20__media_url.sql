-- =====================================================================
-- V20 — Media library moves to Cloudinary (CDN). Cloudinary returns a permanent,
-- public secure_url on upload (embedded in the dashboard, public website, and
-- emails), so we store it directly instead of minting presigned URLs at read time.
-- storage_key now holds the Cloudinary public_id (the delete handle).
-- =====================================================================

ALTER TABLE media_assets ADD COLUMN url TEXT;
