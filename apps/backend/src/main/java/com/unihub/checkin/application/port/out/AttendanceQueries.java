package com.unihub.checkin.application.port.out;

import com.unihub.shared.domain.id.RegistrationId;
import java.util.Collection;

/** Read model of {@code attendances} (backend-coding-standards.md §20). */
public interface AttendanceQueries {

    /** How many of these registrations have an attendance. */
    long countFor(Collection<RegistrationId> registrations);

    /**
     * The attendances of these registrations, earliest check-in first (the registration id breaks
     * ties, so a page boundary is stable). A page past the last is empty, not an error.
     */
    AttendancePage pageFor(Collection<RegistrationId> registrations, int page, int size);
}
