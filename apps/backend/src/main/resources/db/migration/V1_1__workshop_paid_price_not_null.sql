-- V1's price CHECK read `... OR (is_paid = true AND price > 0)`. For a paid workshop with a NULL
-- price, `NULL > 0` is unknown, and a CHECK accepts unknown, so the row was admitted. Re-state the
-- constraint with an explicit IS NOT NULL so the database refuses what the Workshop aggregate refuses.
-- Version 1.1 (not 2) keeps V2__seed_default_roles_permissions.sql free (deployment-and-cicd.md §4).
-- Tightening only: pods of the previous version never legitimately wrote such a row, so a rolling
-- deploy is safe in both directions.
-- workshops_check1 is the name Postgres generated for V1's unnamed multi-column price CHECK.
ALTER TABLE workshops DROP CONSTRAINT workshops_check1;

ALTER TABLE workshops
    ADD CONSTRAINT chk_workshop_price
    CHECK ((is_paid = false AND price IS NULL)
        OR (is_paid = true AND price IS NOT NULL AND price > 0));
