package com.unihub.checkin.application.port.out;

import com.unihub.shared.domain.id.RegistrationId;
import java.time.Instant;

/** One row of {@code attendances}: the registration that checked in and when. */
public record AttendanceEntry(RegistrationId registrationId, Instant checkedInAt) {}
