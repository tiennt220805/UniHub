package com.unihub.shared.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.shared.application.command.RecordAuditEventCommand;
import com.unihub.shared.application.command.RecordAuditEventCommand.ActorType;
import com.unihub.shared.application.port.out.AuditEventStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The audit consumer's use case hands the store exactly what the message said, keyed by the message
 * id (Phase 6 checklist D69). Idempotency itself is the store's unique index, proven against a real
 * database by {@code AuditRedeliveryIT}.
 */
class RecordAuditEventTest {

    private static final UUID MESSAGE = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();
    private static final Instant AT = Instant.parse("2026-11-03T08:00:00Z");

    private final List<AuditEventStore.Row> appended = new ArrayList<>();
    private final RecordAuditEvent useCase =
            new RecordAuditEvent(
                    row -> {
                        appended.add(row);
                        return true;
                    });

    @Test
    @DisplayName("a user's event is appended with the message id as its source")
    void userEvent() {
        useCase.record(
                new RecordAuditEventCommand(
                        MESSAGE,
                        ActorType.USER,
                        USER,
                        "WORKSHOP_CANCELLED",
                        "WORKSHOP",
                        TARGET,
                        AT,
                        Map.of("reason", "x")));

        assertThat(appended)
                .singleElement()
                .isEqualTo(
                        new AuditEventStore.Row(
                                MESSAGE,
                                "USER",
                                USER,
                                "WORKSHOP_CANCELLED",
                                "WORKSHOP",
                                TARGET,
                                AT,
                                Map.of("reason", "x")));
    }

    @Test
    @DisplayName("a system event has no user, and a missing metadata is an empty map")
    void systemEvent() {
        useCase.record(
                new RecordAuditEventCommand(
                        MESSAGE,
                        ActorType.SYSTEM,
                        null,
                        "REGISTRATION_EXPIRED",
                        "REGISTRATION",
                        TARGET,
                        AT,
                        null));

        assertThat(appended.getFirst().actorType()).isEqualTo("SYSTEM");
        assertThat(appended.getFirst().actorUserId()).isNull();
        assertThat(appended.getFirst().metadata()).isEmpty();
    }

    @Test
    @DisplayName("the command refuses a USER without a user and a SYSTEM with one")
    void actorAndUserMustAgree() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                new RecordAuditEventCommand(
                                        MESSAGE,
                                        ActorType.USER,
                                        null,
                                        "A",
                                        "T",
                                        TARGET,
                                        AT,
                                        Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                new RecordAuditEventCommand(
                                        MESSAGE,
                                        ActorType.SYSTEM,
                                        USER,
                                        "A",
                                        "T",
                                        TARGET,
                                        AT,
                                        Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
