package com.unihub.shared.infrastructure.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.unihub.shared.application.command.RecordAuditEventCommand;
import com.unihub.shared.application.command.RecordAuditEventCommand.ActorType;
import com.unihub.shared.application.port.in.RecordAuditEventUseCase;
import com.unihub.shared.infrastructure.AuditITSupport;
import com.unihub.shared.infrastructure.Redeliveries;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

/**
 * INV-11 for the audit consumer (Phase 6 checklist 2.3, 2.4, D69, M-40; AC69.1, AC69.2): the same
 * message delivered again, and handled at the same time by two threads, leaves exactly one {@code
 * audit_events} row. What identifies an audit message is its AMQP {@code message-id}, enforced by
 * the unique index of migration {@code V8}; it is not its content, so two messages with the same
 * content and different ids are two rows.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest
class AuditRedeliveryIT extends AuditITSupport {

    @Autowired private RecordAuditEventUseCase recordAuditEvent;

    @Test
    @DisplayName("INV-11: one message delivered three times is one row")
    void sequentialRedelivery_writesOneRow() {
        var target = aTarget();
        var message = UUID.randomUUID();
        var body = auditBody("WORKSHOP_CANCELLED", target);

        publishAudit(message, body);
        publishAudit(message, body);
        publishAudit(message, body);
        // The consumer is one thread and takes messages in order: once the message published last
        // has been written, the three before it have been handled.
        var last = UUID.randomUUID();
        publishAudit(last, auditBody("WORKSHOP_UPDATED", target));

        await().atMost(Duration.ofSeconds(20)).until(() -> rowsOf(target).size() == 2);
        assertThat(rowsOf(target))
                .extracting(
                        row -> row.get("action"), row -> row.get("source_message_id").toString())
                .containsExactlyInAnyOrder(
                        org.assertj.core.api.Assertions.tuple(
                                "WORKSHOP_CANCELLED", message.toString()),
                        org.assertj.core.api.Assertions.tuple("WORKSHOP_UPDATED", last.toString()));
        assertThat(queueDepth(QUEUE)).isZero();
        // A redelivery that failed instead of doing nothing would still leave one row (the unique
        // index sees to that) but reach the parking lot after the retry chain (3 x 0.3 s here):
        // wait out the chain and show the lot stayed empty.
        await().during(Duration.ofSeconds(2))
                .atMost(Duration.ofSeconds(10))
                .until(() -> queueDepth(PARKING_LOT) == 0);
    }

    @Test
    @DisplayName(
            "the identity is the message-id, not the content: the same content under two ids is"
                    + " two rows")
    void sameContentUnderTwoIds_isTwoRows() {
        var target = aTarget();
        var body = auditBody("WORKSHOP_CANCELLED", target);

        publishAudit(UUID.randomUUID(), body);
        publishAudit(UUID.randomUUID(), body);

        await().atMost(Duration.ofSeconds(20)).until(() -> rowsOf(target).size() == 2);
    }

    @Test
    @DisplayName("INV-11: the same message handled by two threads at once is one row")
    void concurrentRedelivery_writesOneRow() {
        var target = aTarget();
        var command =
                new RecordAuditEventCommand(
                        UUID.randomUUID(),
                        ActorType.SYSTEM,
                        null,
                        "WORKSHOP_CANCELLED",
                        "WORKSHOP",
                        target,
                        Instant.parse("2026-11-03T08:00:00Z"),
                        Map.of("process", "audit-it"));

        // The loser of the race waits for the winner's uncommitted insert and then does nothing:
        // no call is allowed to fail.
        Redeliveries.concurrently(2, () -> recordAuditEvent.record(command)).assertOnlyFailuresOf();

        assertThat(rowsOf(target)).hasSize(1);
    }

    @Test
    @DisplayName(
            "a message without a message-id is parked and writes no row: a null id would slip"
                    + " past the unique index")
    void withoutAMessageId_isParked() {
        var target = aTarget();

        publishAudit(null, auditBody("WORKSHOP_CANCELLED", target));

        await().atMost(Duration.ofSeconds(20)).until(() -> queueDepth(PARKING_LOT) == 1);
        assertThat(rowsOf(target)).isEmpty();
        assertThat(queueDepth(QUEUE)).isZero();
    }

    @Test
    @DisplayName("a body that is not an audit message is parked, not retried")
    void aMalformedBody_isParked() {
        publishAudit(UUID.randomUUID(), "this is not json");

        await().atMost(Duration.ofSeconds(20)).until(() -> queueDepth(PARKING_LOT) == 1);
        assertThat(queueDepth(QUEUE)).isZero();
    }
}
