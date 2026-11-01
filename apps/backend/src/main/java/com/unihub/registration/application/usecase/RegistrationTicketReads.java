package com.unihub.registration.application.usecase;

import com.unihub.registration.api.RegistrationTickets;
import com.unihub.registration.application.port.out.RegistrationRepository;
import com.unihub.shared.application.routing.ReadFromPrimary;
import com.unihub.shared.domain.id.RegistrationId;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class RegistrationTicketReads implements RegistrationTickets {
    private final RegistrationRepository registrations;

    RegistrationTicketReads(RegistrationRepository registrations) {
        this.registrations = registrations;
    }

    @Override
    @Transactional(readOnly = true)
    @ReadFromPrimary
    public Optional<String> qrPayloadOf(RegistrationId registrationId) {
        return registrations.findById(registrationId).map(r -> r.qrPayload());
    }
}
