-- V9 (Phase 8, Muc 3.2): the cover image of a workshop, uploaded by an Organizer (D114, D121, D122). Two
-- nullable keys that mirror the brochure pair of V7 and `brochure_s3_key`: `pending_cover_image_key` is the
-- key of the last presigned upload URL issued, remembered until `confirmCoverImageUpload` finds the object and
-- moves it into `cover_image_key` (the contract's confirm has no request body, so the server must know which
-- key it issued). Specified in docs/technical-design/platform/database-schema.md §10.5.
--
-- Only adds two nullable columns (architecture.md §6.2, INV-15): the previous release's inserts and updates
-- name their columns, so it neither reads nor writes these. Forward-only.

ALTER TABLE workshops ADD COLUMN cover_image_key VARCHAR(512);
ALTER TABLE workshops ADD COLUMN pending_cover_image_key VARCHAR(512);
