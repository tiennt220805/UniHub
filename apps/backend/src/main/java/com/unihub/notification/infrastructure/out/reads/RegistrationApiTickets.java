package com.unihub.notification.infrastructure.out.reads;

import com.unihub.notification.application.port.out.RegistrationTickets;
import com.unihub.shared.domain.id.RegistrationId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@link RegistrationTickets} over {@code registration.api} (Phase 5 checklist D55): translation
 * only.
 */
@Component
class RegistrationApiTickets implements RegistrationTickets {
    private final com.unihub.registration.api.RegistrationTickets tickets;

    RegistrationApiTickets(com.unihub.registration.api.RegistrationTickets tickets) {
        this.tickets = tickets;
    }

    @Override
    public Optional<String> qrPayloadOf(UUID registrationId) {
        return tickets.qrPayloadOf(RegistrationId.of(registrationId));
    }
}
