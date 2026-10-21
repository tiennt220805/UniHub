package com.unihub.registration.application.usecase;

import com.unihub.registration.application.port.in.ListMyRegistrationsUseCase;
import com.unihub.registration.application.port.out.RegistrationRepository;
import com.unihub.registration.application.query.ListMyRegistrationsQuery;
import com.unihub.registration.application.result.MyRegistrationsResult;
import com.unihub.registration.domain.model.RegistrationStatus;
import com.unihub.shared.application.routing.ReadFromPrimary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The caller's own registrations. From the Master, for the same reason as {@code GetRegistration}.
 */
@Service
class ListMyRegistrations implements ListMyRegistrationsUseCase {
    private final RegistrationRepository registrations;

    ListMyRegistrations(RegistrationRepository registrations) {
        this.registrations = registrations;
    }

    @Override
    @Transactional(readOnly = true)
    @ReadFromPrimary
    public MyRegistrationsResult list(ListMyRegistrationsQuery query) {
        return MyRegistrationsResult.of(
                registrations.findByUser(
                        query.caller(),
                        query.status() == null ? null : RegistrationStatus.valueOf(query.status()),
                        query.page(),
                        query.size()));
    }
}
