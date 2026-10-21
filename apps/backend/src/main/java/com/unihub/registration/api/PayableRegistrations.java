package com.unihub.registration.api;

import com.unihub.shared.domain.id.RegistrationId;
import java.util.Optional;

/**
 * Registration's answer to "may this be paid for?" (backend-coding-standards.md §19.5). Must run
 * inside the caller's transaction (its implementation is {@code MANDATORY}), hence on the Master:
 * the charge decides on the registration's current state, which a lagging Replica could still show
 * as {@code RESERVED}.
 */
public interface PayableRegistrations {

    Optional<PayableRegistration> find(RegistrationId id);
}
