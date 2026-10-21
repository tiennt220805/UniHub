package com.unihub.registration.application.usecase;

import com.unihub.registration.api.PayableRegistration;
import com.unihub.registration.api.PayableRegistrations;
import com.unihub.registration.application.port.out.RegistrationRepository;
import com.unihub.registration.domain.model.RegistrationStatus;
import com.unihub.shared.domain.id.RegistrationId;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class RegistrationPayability implements PayableRegistrations {
    private final RegistrationRepository registrations;

    RegistrationPayability(RegistrationRepository registrations) {
        this.registrations = registrations;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PayableRegistration> find(RegistrationId id) {
        return registrations
                .findById(id)
                .map(
                        registration ->
                                new PayableRegistration(
                                        registration.id(),
                                        registration.workshopId(),
                                        registration.userId(),
                                        registration.status() == RegistrationStatus.RESERVED,
                                        registration.holdExpiresAt()));
    }
}
