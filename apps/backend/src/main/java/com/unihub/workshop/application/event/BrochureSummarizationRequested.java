package com.unihub.workshop.application.event;

import com.unihub.shared.event.OutboxMessage;
import java.time.Instant;
import java.util.UUID;

/**
 * A Deferred Job (backend-coding-standards.md §8.4): Workshop tells itself, through the broker,
 * that a brochure was confirmed and wants a summary (workshop.md §3, §7). Written in the same
 * transaction as {@code brochure_s3_key}. The consumer re-reads the workshop and acts on what it
 * finds; the key in the message only lets it recognise a request for a brochure that has been
 * replaced since.
 */
public record BrochureSummarizationRequested(
        UUID workshopId, String brochureKey, Instant occurredAt) implements OutboxMessage {

    public static final String ROUTING_KEY =
            "unihub.workshop.summarization.requested"; // messaging.md §2
    public static final int SCHEMA_VERSION = 1;

    @Override
    public String routingKey() {
        return ROUTING_KEY;
    }

    @Override
    public String aggregateType() {
        return "WORKSHOP";
    }

    @Override
    public UUID aggregateId() {
        return workshopId;
    }

    @Override
    public int schemaVersion() {
        return SCHEMA_VERSION;
    }
}
