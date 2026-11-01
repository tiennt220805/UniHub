package com.unihub.registration.application.usecase;

import com.unihub.registration.api.TicketLookup;
import com.unihub.registration.api.TicketRegistration;
import com.unihub.registration.application.port.out.RegistrationRepository;
import com.unihub.registration.domain.model.Registration;
import com.unihub.shared.application.routing.ReadFromPrimary;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class RegistrationTicketLookup implements TicketLookup {
    private final RegistrationRepository registrations;

    RegistrationTicketLookup(RegistrationRepository registrations) {
        this.registrations = registrations;
    }

    @Override
    @Transactional(readOnly = true)
    @ReadFromPrimary
    public Map<String, TicketRegistration> findByCodes(Collection<String> ticketCodes) {
        if (ticketCodes.isEmpty()) {
            return Map.of();
        }
        var found = new HashMap<String, TicketRegistration>();
        for (var registration : registrations.findByTicketCodes(ticketCodes)) {
            found.put(registration.ticketCode(), ticketRegistration(registration));
        }
        return found;
    }

    private static TicketRegistration ticketRegistration(Registration registration) {
        return new TicketRegistration(
                registration.id(), registration.workshopId(), registration.isConfirmed());
    }
}
