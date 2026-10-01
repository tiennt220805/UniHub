-- Transcribed verbatim from docs/technical-design/platform/database-schema.md §10 (DDL Appendix,
-- lines 577-857). That document is the authority for column types/constraints — this file must
-- never diverge from it; if a change is needed, it is made there first (CLAUDE.md, backend-coding-
-- standards.md §27 precedence rule).

-- ============================================================
-- 2. User Module
-- ============================================================

CREATE TABLE users (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email               VARCHAR(255) NOT NULL UNIQUE,
    password_hash       VARCHAR(255),
    full_name           VARCHAR(255) NOT NULL,
    legacy_student_id   VARCHAR(50) UNIQUE,
    status              VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                            CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE roles (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(100) NOT NULL UNIQUE,
    description TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE permissions (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code        VARCHAR(100) NOT NULL UNIQUE,
    description TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE user_roles (
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    role_id     UUID NOT NULL REFERENCES roles(id) ON DELETE RESTRICT,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE role_permissions (
    role_id       UUID NOT NULL REFERENCES roles(id) ON DELETE RESTRICT,
    permission_id UUID NOT NULL REFERENCES permissions(id) ON DELETE RESTRICT,
    assigned_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (role_id, permission_id)
);

-- legacy_sync_runs declared before legacy_sync_errors so the latter's
-- REFERENCES clause resolves against a table that already exists.
CREATE TABLE legacy_sync_runs (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    export_date         DATE NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'RUNNING'
                            CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    failure_reason      VARCHAR(50)
                            CHECK (failure_reason IN ('FILE_NOT_FOUND', 'FILE_UNREADABLE', 'PIPELINE_ERROR')),
    failure_detail      TEXT,
    started_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at         TIMESTAMPTZ,
    rows_processed      INT NOT NULL DEFAULT 0,
    rows_created        INT NOT NULL DEFAULT 0,
    rows_updated        INT NOT NULL DEFAULT 0,
    rows_quarantined    INT NOT NULL DEFAULT 0
);

CREATE INDEX idx_legacy_sync_runs_export_date ON legacy_sync_runs (export_date);
CREATE INDEX idx_legacy_sync_runs_failed ON legacy_sync_runs (status) WHERE status = 'FAILED';

CREATE TABLE legacy_sync_errors (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sync_run_id     UUID NOT NULL REFERENCES legacy_sync_runs(id) ON DELETE RESTRICT,
    row_number      INT NOT NULL,
    raw_row         TEXT NOT NULL,
    reason_code     VARCHAR(50) NOT NULL
                        CHECK (reason_code IN ('MALFORMED_ROW', 'INVALID_FIELD', 'DUPLICATE_IN_FILE')),
    reason_detail   TEXT,
    reviewed        BOOLEAN NOT NULL DEFAULT false,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_legacy_sync_errors_run ON legacy_sync_errors (sync_run_id);
CREATE INDEX idx_legacy_sync_errors_reviewed ON legacy_sync_errors (reviewed) WHERE reviewed = false;

-- ============================================================
-- 3. Workshop Module
-- ============================================================

CREATE TABLE workshops (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title            VARCHAR(255) NOT NULL,
    description      TEXT,
    ai_summary       TEXT,
    speaker_name     VARCHAR(255) NOT NULL,
    speaker_bio      TEXT,
    location         VARCHAR(255) NOT NULL,
    start_time       TIMESTAMPTZ NOT NULL,
    end_time         TIMESTAMPTZ NOT NULL,
    capacity         INT NOT NULL CHECK (capacity >= 0),
    seats_taken      INT NOT NULL DEFAULT 0
                         CHECK (seats_taken >= 0 AND seats_taken <= capacity),
    is_paid          BOOLEAN NOT NULL DEFAULT false,
    price            NUMERIC(12,2)
                         CHECK ((is_paid = false AND price IS NULL)
                             OR (is_paid = true AND price > 0)),
    brochure_s3_key  VARCHAR(512),
    status           VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
                         CHECK (status IN ('DRAFT', 'PUBLISHED', 'CANCELLED', 'COMPLETED')),
    version          INT NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_workshop_time_order CHECK (end_time > start_time)
);

CREATE INDEX idx_workshops_status_start_time ON workshops (status, start_time);

-- ============================================================
-- 4. Registration Module
-- ============================================================

CREATE TABLE registrations (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workshop_id      UUID NOT NULL REFERENCES workshops(id) ON DELETE RESTRICT,
    user_id          UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    ticket_type      VARCHAR(10) NOT NULL CHECK (ticket_type IN ('FREE', 'PAID')),
    status           VARCHAR(20) NOT NULL
                         CHECK (status IN ('RESERVED', 'CONFIRMED', 'CANCELLED', 'EXPIRED')),
    hold_expires_at  TIMESTAMPTZ
                         CHECK ((status = 'RESERVED') = (hold_expires_at IS NOT NULL)),
    ticket_code      VARCHAR(64) UNIQUE,
    qr_payload       TEXT,
    version          INT NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_registrations_active_per_user
    ON registrations (workshop_id, user_id)
    WHERE status IN ('RESERVED', 'CONFIRMED');

CREATE INDEX idx_registrations_reserved_hold
    ON registrations (hold_expires_at)
    WHERE status = 'RESERVED';

CREATE INDEX idx_registrations_workshop_id ON registrations (workshop_id);
CREATE INDEX idx_registrations_user_id ON registrations (user_id);

-- ============================================================
-- 5. Payment Module
-- ============================================================

CREATE TABLE payments (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    registration_id         UUID NOT NULL UNIQUE
                                REFERENCES registrations(id) ON DELETE RESTRICT,
    amount                  NUMERIC(12,2) NOT NULL CHECK (amount > 0),
    currency                VARCHAR(3) NOT NULL DEFAULT 'VND',
    gateway                 VARCHAR(20) NOT NULL DEFAULT 'STRIPE',
    gateway_transaction_id  VARCHAR(255) UNIQUE,
    status                  VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                                CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    initiated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at             TIMESTAMPTZ,
    version                 INT NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_payments_pending_scan
    ON payments (initiated_at)
    WHERE status = 'PENDING';

-- ============================================================
-- 6. Check-in Module
-- ============================================================

CREATE TABLE check_in_scans (
    id              UUID PRIMARY KEY,
    registration_id UUID NOT NULL REFERENCES registrations(id) ON DELETE RESTRICT,
    scanned_by      UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    device_id       VARCHAR(100) NOT NULL,
    scanned_at      TIMESTAMPTZ NOT NULL,
    received_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    outcome         VARCHAR(20) NOT NULL
                        CHECK (outcome IN ('ACCEPTED', 'REJECTED_DUPLICATE', 'REJECTED_INVALID')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_check_in_scans_registration_id ON check_in_scans (registration_id);
CREATE INDEX idx_check_in_scans_device_id ON check_in_scans (device_id);

CREATE TABLE attendances (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    registration_id  UUID NOT NULL UNIQUE
                         REFERENCES registrations(id) ON DELETE RESTRICT,
    winning_scan_id  UUID NOT NULL UNIQUE
                         REFERENCES check_in_scans(id) ON DELETE RESTRICT,
    checked_in_at    TIMESTAMPTZ NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============================================================
-- 7. Notification Module
-- ============================================================

CREATE TABLE notifications (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id              UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    channel              VARCHAR(20) NOT NULL CHECK (channel IN ('EMAIL', 'IN_APP')),
    type                 VARCHAR(30) NOT NULL
                             CHECK (type IN ('REGISTRATION_CONFIRMED', 'WORKSHOP_CANCELLED', 'WORKSHOP_RESCHEDULED')),
    related_entity_type  VARCHAR(20) NOT NULL CHECK (related_entity_type IN ('WORKSHOP', 'REGISTRATION')),
    related_entity_id    UUID NOT NULL,
    status               VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                             CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    sent_at              TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_notifications_user_status ON notifications (user_id, status);

-- ============================================================
-- 8. Technical Pattern Entities
-- ============================================================

CREATE TABLE idempotency_keys (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key  VARCHAR(255) NOT NULL,
    scope            VARCHAR(50) NOT NULL,
    response_status  INT NOT NULL,
    response_body    JSONB NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_idempotency_keys_scope_key UNIQUE (scope, idempotency_key)
);

CREATE INDEX idx_idempotency_keys_created_at ON idempotency_keys (created_at);

CREATE TABLE event_publications (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id   UUID NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    routing_key    VARCHAR(100) NOT NULL,
    payload        JSONB NOT NULL,
    correlation_id VARCHAR(32),
    status         VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                       CHECK (status IN ('PENDING', 'PUBLISHED')),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ
);

CREATE INDEX idx_event_publications_pending
    ON event_publications (created_at)
    WHERE status = 'PENDING';

CREATE TABLE audit_events (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_type     VARCHAR(10) NOT NULL DEFAULT 'USER'
                       CHECK (actor_type IN ('USER', 'SYSTEM')),
    actor_user_id  UUID REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT ck_audit_events_actor
        CHECK ((actor_type = 'USER') = (actor_user_id IS NOT NULL)),
    action         VARCHAR(100) NOT NULL,
    target_type    VARCHAR(50) NOT NULL,
    target_id      UUID NOT NULL,
    metadata       JSONB,
    occurred_at    TIMESTAMPTZ NOT NULL,
    recorded_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_events_target ON audit_events (target_type, target_id);
CREATE INDEX idx_audit_events_occurred_at ON audit_events (occurred_at);
