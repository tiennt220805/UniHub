-- V7 (Phase 5, Mục 9.2): where the server remembers the brochure key it issued, between issuing a presigned
-- upload URL and confirming the upload. Specified in docs/technical-design/platform/database-schema.md §10.4
-- (decision delegated by the owner 2026-11-01); the contract's `confirmBrochureUpload` has no request body
-- and promises a 409 for "never issued", so the key has to be kept on the server, and PostgreSQL is the
-- authority for brochure state (workshop.md §1, §10).
--
-- Only adds a nullable column (architecture.md §6.2): the old pod's inserts and updates name their columns,
-- so it neither reads nor writes this one. Forward-only.

ALTER TABLE workshops ADD COLUMN pending_brochure_key VARCHAR(512);
