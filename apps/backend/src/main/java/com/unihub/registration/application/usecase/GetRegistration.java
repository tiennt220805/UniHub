package com.unihub.registration.application.usecase;

import com.unihub.registration.application.port.in.GetRegistrationUseCase;
import com.unihub.registration.application.port.out.RegistrationRepository;
import com.unihub.registration.application.query.GetRegistrationQuery;
import com.unihub.registration.application.result.RegistrationResult;
import com.unihub.registration.domain.exception.RegistrationNotFoundException;
import com.unihub.registration.domain.exception.RegistrationNotOwnedException;
import com.unihub.shared.application.routing.ReadFromPrimary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads one registration for its owner. From the Master, not the Replica: this is the view a
 * student polls while a payment is being confirmed and right after registering, and a lagging
 * Replica would show a stale {@code RESERVED} (INV-03, backend-coding-standards.md §20).
 */
@Service
class GetRegistration implements GetRegistrationUseCase {
    private final RegistrationRepository registrations;

    GetRegistration(RegistrationRepository registrations) {
        this.registrations = registrations;
    }

    @Override
    @Transactional(readOnly = true)
    @ReadFromPrimary
    public RegistrationResult get(GetRegistrationQuery query) {
        var registration =
                registrations
                        .findById(query.registrationId())
                        .orElseThrow(
                                () -> new RegistrationNotFoundException(query.registrationId()));
        if (!registration.userId().equals(query.caller())) {
            throw new RegistrationNotOwnedException(query.registrationId());
        }
        return RegistrationResult.of(registration);
    }
}
