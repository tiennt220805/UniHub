package com.unihub.registration.application.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.registration.api.event.RegistrationConfirmedIntegrationEvent;
import com.unihub.registration.domain.event.RegistrationConfirmed;
import com.unihub.registration.domain.event.RegistrationDomainEvent;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.domain.id.WorkshopId;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RegistrationOutboxTranslatorTest {

    private static final RegistrationId REGISTRATION = RegistrationId.newId();
    private static final WorkshopId WORKSHOP = WorkshopId.newId();
    private static final UserId USER = UserId.newId();
    private static final Instant AT = Instant.parse("2026-10-21T08:00:00Z");

    @Test
    @DisplayName("RegistrationConfirmed becomes one ticket.confirmed message with flat UUIDs")
    void confirmed() {
        var messages =
                RegistrationOutboxTranslator.translate(
                        new RegistrationConfirmed(REGISTRATION, WORKSHOP, USER, AT));

        assertThat(messages).hasSize(1);
        var message = messages.getFirst();
        assertThat(message)
                .isEqualTo(
                        new RegistrationConfirmedIntegrationEvent(
                                REGISTRATION.value(), WORKSHOP.value(), USER.value(), AT));
        assertThat(message.routingKey()).isEqualTo("unihub.registration.ticket.confirmed");
        assertThat(message.aggregateType()).isEqualTo("REGISTRATION");
        assertThat(message.aggregateId()).isEqualTo(REGISTRATION.value());
        assertThat(message.schemaVersion()).isEqualTo(1);
        assertThat(message.occurredAt()).isEqualTo(AT);
    }

    @Test
    @DisplayName("every permitted domain event has a translation (the switch is exhaustive)")
    void everyPermittedEvent_isCovered() {
        for (var permitted : RegistrationDomainEvent.class.getPermittedSubclasses()) {
            assertThat(permitted)
                    .as("a new Registration event needs a case in the translator and a test here")
                    .isIn(RegistrationConfirmed.class);
        }
    }
}
