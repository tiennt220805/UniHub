package com.unihub.registration.domain.exception;

import com.unihub.shared.domain.DomainException;
import com.unihub.shared.domain.ProblemKind;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.domain.id.WorkshopId;

/**
 * The partial unique index {@code uq_registrations_active_per_user} refused a second active
 * registration. The use case checks first and answers with a Result; this is the backstop for a
 * request that raced past the check, and it must still be a {@code 409}, never a {@code 500}.
 */
public class AlreadyRegisteredException extends DomainException {

    public AlreadyRegisteredException(UserId userId, WorkshopId workshopId) {
        super("User " + userId.value() + " already holds a registration for " + workshopId.value());
    }

    @Override
    public ProblemKind kind() {
        return ProblemKind.CONFLICT;
    }

    @Override
    public String problemType() {
        // Same value as ProblemTypes.ALREADY_REGISTERED, which the domain cannot import.
        return "urn:unihub:problem:already-registered";
    }
}
