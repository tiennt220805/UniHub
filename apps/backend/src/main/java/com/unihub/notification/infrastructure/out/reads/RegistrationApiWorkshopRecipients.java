package com.unihub.notification.infrastructure.out.reads;

import com.unihub.notification.application.port.out.RegistrationRef;
import com.unihub.notification.application.port.out.WorkshopRecipients;
import com.unihub.registration.api.Attendee;
import com.unihub.registration.api.WorkshopAttendees;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.WorkshopId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@link WorkshopRecipients} over {@code registration.api} (Phase 4 checklist D35): translation
 * only.
 */
@Component
class RegistrationApiWorkshopRecipients implements WorkshopRecipients {
    private final WorkshopAttendees attendees;

    RegistrationApiWorkshopRecipients(WorkshopAttendees attendees) {
        this.attendees = attendees;
    }

    @Override
    public List<RegistrationRef> holdingSeats(UUID workshopId) {
        return attendees.holdingSeatsFor(WorkshopId.of(workshopId)).stream()
                .map(RegistrationApiWorkshopRecipients::ref)
                .toList();
    }

    @Override
    public Optional<RegistrationRef> find(UUID registrationId) {
        return attendees
                .find(RegistrationId.of(registrationId))
                .map(RegistrationApiWorkshopRecipients::ref);
    }

    private static RegistrationRef ref(Attendee attendee) {
        return new RegistrationRef(
                attendee.registrationId().value(),
                attendee.userId().value(),
                attendee.workshopId().value());
    }
}
