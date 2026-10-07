-- Default roles/permissions, from docs/technical-design/platform/deployment-and-cicd.md §4.
-- Idempotent (ON CONFLICT DO NOTHING): roles.name and permissions.code are UNIQUE, and
-- role_permissions' composite PK gives the same conflict target for the bundling step.
-- Role names here are display labels only; no API check ever compares against them
-- (architecture.md §5.1) — every check is on permissions.code.

INSERT INTO roles (id, name) VALUES
    (gen_random_uuid(), 'Student'),
    (gen_random_uuid(), 'Organizer'),
    (gen_random_uuid(), 'Admin'),
    (gen_random_uuid(), 'Check-in Staff')
ON CONFLICT (name) DO NOTHING;

INSERT INTO permissions (id, code) VALUES
    (gen_random_uuid(), 'WORKSHOP_CREATE'),
    (gen_random_uuid(), 'WORKSHOP_EDIT'),
    (gen_random_uuid(), 'CHECKIN_SCAN'),
    (gen_random_uuid(), 'REPORT_VIEW')
ON CONFLICT (code) DO NOTHING;

-- Bundling looks roles/permissions up by name/code, not by the UUIDs generated above: on a re-run
-- the inserts above are no-ops, so the ids generated on THIS run may not be the stored ones.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r, permissions p
WHERE (r.name, p.code) IN (
    ('Organizer', 'WORKSHOP_CREATE'),
    ('Organizer', 'WORKSHOP_EDIT'),
    ('Admin', 'WORKSHOP_CREATE'),
    ('Admin', 'WORKSHOP_EDIT'),
    ('Admin', 'REPORT_VIEW'),
    ('Check-in Staff', 'CHECKIN_SCAN')
)
ON CONFLICT (role_id, permission_id) DO NOTHING;
