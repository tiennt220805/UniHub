package com.unihub.workshop.application.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.api.event.WorkshopCancelledIntegrationEvent;
import com.unihub.workshop.api.event.WorkshopRescheduledIntegrationEvent;
import com.unihub.workshop.domain.event.WorkshopCancelled;
import com.unihub.workshop.domain.event.WorkshopDomainEvent;
import com.unihub.workshop.domain.event.WorkshopRescheduled;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WorkshopOutboxTranslatorTest {

    private static final WorkshopId ID = WorkshopId.newId();
    private static final Instant AT = Instant.parse("2026-10-19T08:00:00Z");

    @Test
    @DisplayName("WorkshopCancelled becomes one cancelled message on the documented routing key")
    void cancelled() {
        var messages = WorkshopOutboxTranslator.translate(new WorkshopCancelled(ID, AT));

        assertThat(messages).hasSize(1);
        var message = messages.getFirst();
        assertThat(message).isEqualTo(new WorkshopCancelledIntegrationEvent(ID.value(), AT));
        assertThat(message.routingKey()).isEqualTo("unihub.workshop.event.cancelled");
        assertThat(message.aggregateType()).isEqualTo("WORKSHOP");
        assertThat(message.aggregateId()).isEqualTo(ID.value());
        assertThat(message.schemaVersion()).isEqualTo(1);
        assertThat(message.occurredAt()).isEqualTo(AT);
    }

    @Test
    @DisplayName("WorkshopRescheduled becomes one rescheduled message carrying old and new times")
    void rescheduled() {
        var oldStart = Instant.parse("2026-12-01T09:00:00Z");
        var oldEnd = Instant.parse("2026-12-01T11:00:00Z");
        var newStart = Instant.parse("2026-12-02T09:00:00Z");
        var newEnd = Instant.parse("2026-12-02T11:00:00Z");

        var messages =
                WorkshopOutboxTranslator.translate(
                        new WorkshopRescheduled(ID, oldStart, oldEnd, newStart, newEnd, AT));

        assertThat(messages).hasSize(1);
        var message = messages.getFirst();
        assertThat(message)
                .isEqualTo(
                        new WorkshopRescheduledIntegrationEvent(
                                ID.value(), oldStart, oldEnd, newStart, newEnd, AT));
        assertThat(message.routingKey()).isEqualTo("unihub.workshop.event.rescheduled");
        assertThat(message.aggregateType()).isEqualTo("WORKSHOP");
        assertThat(message.aggregateId()).isEqualTo(ID.value());
    }

    @Test
    @DisplayName("every permitted domain event has a translation (the switch is exhaustive)")
    void everyPermittedEvent_isCovered() {
        for (var permitted : WorkshopDomainEvent.class.getPermittedSubclasses()) {
            assertThat(permitted)
                    .as("a new Workshop event needs a case in the translator and a test here")
                    .isIn(WorkshopCancelled.class, WorkshopRescheduled.class);
        }
    }
}
