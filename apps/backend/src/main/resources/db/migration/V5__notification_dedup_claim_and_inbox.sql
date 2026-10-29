-- V5 (Phase 4, Mục 1.1): what the first notification consumer needs from `notifications`.
-- Specified in docs/technical-design/platform/database-schema.md §10.2; decisions D31 (dedup key),
-- D33 (send lease), D38 (stored in-app content, inbox index).
--
-- Additive only (architecture.md §6.2): the old pod never writes `notifications`, since no consumer
-- existed before Phase 4. source_event_id is NOT NULL without a default on purpose: the table is empty
-- in every environment, and if it ever is not, this migration must fail rather than invent an event id.

ALTER TABLE notifications ADD COLUMN source_event_id UUID NOT NULL;
ALTER TABLE notifications ADD COLUMN claimed_at TIMESTAMPTZ;
ALTER TABLE notifications ADD COLUMN title VARCHAR(200);
ALTER TABLE notifications ADD COLUMN body TEXT;

ALTER TABLE notifications
    ADD CONSTRAINT uq_notifications_source_event_user_channel
    UNIQUE (source_event_id, user_id, channel);

CREATE INDEX idx_notifications_inbox ON notifications (user_id, channel, created_at DESC);
