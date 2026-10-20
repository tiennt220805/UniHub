-- Permission guarding createRegistration and chargePayment (Phase 2 checklist D7, database-schema.md §10.1).
-- Same shape as V2: idempotent (ON CONFLICT DO NOTHING). Bundling looks the role and permission up by
-- name/code, not by generated UUIDs, so a re-run is a no-op. Forward-only: withdrawing the permission is a
-- new migration. Dev/test Student accounts are NOT seeded here (user.md §10) — they never enter a
-- production migration.

INSERT INTO permissions (id, code) VALUES (gen_random_uuid(), 'REGISTRATION_CREATE')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name = 'Student' AND p.code = 'REGISTRATION_CREATE'
ON CONFLICT (role_id, permission_id) DO NOTHING;
