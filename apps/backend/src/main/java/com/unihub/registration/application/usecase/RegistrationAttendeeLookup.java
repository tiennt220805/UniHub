package com.unihub.registration.application.usecase;

import com.unihub.registration.api.Attendee;
import com.unihub.registration.api.WorkshopAttendees;
import com.unihub.registration.application.port.out.RegistrationRepository;
import com.unihub.registration.domain.model.Registration;
import com.unihub.shared.application.routing.ReadFromPrimary;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.WorkshopId;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class RegistrationAttendeeLookup implements WorkshopAttendees {
    private final RegistrationRepository registrations;

    RegistrationAttendeeLookup(RegistrationRepository registrations) {
        this.registrations = registrations;
    }

    @Override
    @Transactional(readOnly = true)
    @ReadFromPrimary
    public List<Attendee> holdingSeatsFor(WorkshopId workshopId) {
        return registrations.findHoldingSeats(workshopId).stream()
                .map(RegistrationAttendeeLookup::attendee)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    @ReadFromPrimary
    public Optional<Attendee> find(RegistrationId registrationId) {
        return registrations.findById(registrationId).map(RegistrationAttendeeLookup::attendee);
    }

    private static Attendee attendee(Registration registration) {
        return new Attendee(registration.id(), registration.userId(), registration.workshopId());
    }
}
