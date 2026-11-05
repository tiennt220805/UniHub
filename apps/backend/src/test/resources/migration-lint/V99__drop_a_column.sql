-- A fixture of MigrationLintTest, never applied: a contract step with no explanation. The lint must
-- fail it on the DROP COLUMN below (line 6).
CREATE INDEX ix_fixture ON workshops (title);

ALTER TABLE workshops DROP COLUMN speaker_name;
