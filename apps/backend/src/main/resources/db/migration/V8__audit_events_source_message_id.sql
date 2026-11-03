-- V8 (Phase 6, Mục 2.1): ties an audit_events row to the AMQP message that produced it, so the audit consumer
-- is idempotent under redelivery (CLAUDE.md invariant; messaging.md §3, Phase 6 checklist D69). The consumer
-- inserts ON CONFLICT (source_message_id) DO NOTHING. Specified in docs/technical-design/platform/database-schema.md
-- §10.5.
--
-- Only adds a nullable column and a unique index (architecture.md §6.2): the old pod never writes audit_events
-- (nothing consumed unihub.audit.queue before Phase 6), and a unique index allows any number of NULLs. Built
-- without CONCURRENTLY because Flyway runs the migration in a transaction; the table is empty on every
-- environment. Forward-only.

ALTER TABLE audit_events ADD COLUMN source_message_id UUID;
CREATE UNIQUE INDEX uq_audit_events_source_message ON audit_events (source_message_id);
