-- Test only (Phase 4 D40, M-39): the additive schema step a new notification channel takes, run by
-- NotificationChannelPluggabilityIT on a database of its own. Never on the shared test database and
-- never in src/main: the shared schema must stay exactly what production gets.
ALTER TABLE notifications DROP CONSTRAINT notifications_channel_check;
ALTER TABLE notifications
    ADD CONSTRAINT notifications_channel_check CHECK (channel IN ('EMAIL', 'IN_APP', 'TEST_PIGEON'));
