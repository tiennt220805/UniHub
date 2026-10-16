-- Phase 2 schema changes (database-schema.md §10.1; checklist D1, C2, C6/D5, C10/D2, G2/C27).
--
-- Both tables are empty in every environment when this runs (no code before Phase 2 reads or writes
-- them). The NOT NULL columns below have no default on purpose: if this ever meets a row, the
-- migration fails instead of inventing values. Once a deployed version writes idempotency_keys, any
-- later change must be expand -> migrate -> contract across releases, never drop-and-add (INV-15,
-- architecture.md §6.2).

-- 8.1 idempotency_keys: store the use-case Result, scope the key by actor, add fingerprint + lifecycle.
ALTER TABLE idempotency_keys
    DROP CONSTRAINT uq_idempotency_keys_scope_key,
    DROP COLUMN response_status,
    DROP COLUMN response_body,
    ADD COLUMN actor_user_id       UUID         NOT NULL,
    ADD COLUMN request_fingerprint VARCHAR(64)  NOT NULL,
    ADD COLUMN status              VARCHAR(20)  NOT NULL,
    ADD COLUMN result_type         VARCHAR(100),
    ADD COLUMN result_body         JSONB,
    ADD COLUMN resource_id         UUID,
    ADD COLUMN completed_at        TIMESTAMPTZ,
    ADD CONSTRAINT ck_idempotency_keys_status
        CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    ADD CONSTRAINT ck_idempotency_keys_completed_result
        CHECK ((status = 'COMPLETED') = (result_type IS NOT NULL)),
    ADD CONSTRAINT ck_idempotency_keys_result_pair
        CHECK ((result_type IS NULL) = (result_body IS NULL)),
    ADD CONSTRAINT ck_idempotency_keys_completed_at
        CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL)),
    ADD CONSTRAINT uq_idempotency_keys_scope_actor_key
        UNIQUE (scope, actor_user_id, idempotency_key);

DROP INDEX idx_idempotency_keys_created_at;
CREATE INDEX idx_idempotency_keys_completed_at
    ON idempotency_keys (completed_at) WHERE status = 'COMPLETED';
CREATE INDEX idx_idempotency_keys_open_resource
    ON idempotency_keys (scope, resource_id) WHERE status = 'IN_PROGRESS';

-- 5.1 payments: at most one live payment per registration; refund statuses.
-- The partial index is created before the table-wide UNIQUE is dropped, so "no two live payments for
-- one registration" is enforced at every instant.
CREATE UNIQUE INDEX uq_payments_live_per_registration
    ON payments (registration_id)
    WHERE status IN ('PENDING', 'SUCCEEDED');
ALTER TABLE payments DROP CONSTRAINT payments_registration_id_key;
ALTER TABLE payments DROP CONSTRAINT payments_status_check;
ALTER TABLE payments ADD CONSTRAINT ck_payments_status
    CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'REFUND_REQUIRED', 'REFUNDED'));
