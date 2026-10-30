-- V6 (Phase 5, Mục 1.1): what the first Check-in code needs from the schema.
-- Specified in docs/technical-design/platform/database-schema.md §10.3; decisions D50 (a scan the device
-- rejected reaches the server with no registration) and D46 (Organizers read the workshop report).
--
-- Only loosens or adds (architecture.md §6.2): the old pod never writes `check_in_scans` (no Check-in code
-- exists before Phase 5), so dropping NOT NULL and adding a CHECK validate nothing existing. The role
-- seed follows V2/V4: idempotent, looks the role and permission up by name/code, never by generated UUID.
-- Forward-only: withdrawing the permission would be a new migration.

ALTER TABLE check_in_scans ALTER COLUMN registration_id DROP NOT NULL;
ALTER TABLE check_in_scans
    ADD CONSTRAINT chk_check_in_scans_unresolved_is_invalid
    CHECK (registration_id IS NOT NULL OR outcome = 'REJECTED_INVALID');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r, permissions p
WHERE r.name = 'Organizer' AND p.code = 'REPORT_VIEW'
ON CONFLICT (role_id, permission_id) DO NOTHING;
