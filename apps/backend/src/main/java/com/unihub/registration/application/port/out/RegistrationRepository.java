package com.unihub.registration.application.port.out;

import com.unihub.registration.domain.model.Registration;
import com.unihub.registration.domain.model.RegistrationStatus;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.domain.id.WorkshopId;
import java.util.Optional;

public interface RegistrationRepository {

    /** Absent is an empty Optional; the use case decides what that means. */
    Optional<Registration> findById(RegistrationId id);

    /** Whether the student holds a {@code RESERVED} or {@code CONFIRMED} registration here. */
    boolean existsActive(WorkshopId workshopId, UserId userId);

    /**
     * Inserts a new registration, or updates an existing one if its version is still current.
     *
     * @throws com.unihub.shared.domain.OptimisticConflictException when the version is stale
     * @throws com.unihub.registration.domain.exception.AlreadyRegisteredException when the student
     *     already holds an active registration for the workshop
     */
    Registration save(Registration registration);

    /**
     * Persists a status change only if the row is still in {@code from} at the aggregate's version:
     * {@code UPDATE … WHERE id = ? AND version = ? AND status = :from}. This is what makes
     * confirm-versus-expire exactly-one-wins (Phase 2 checklist D2 constraint 1).
     *
     * @throws com.unihub.shared.domain.OptimisticConflictException when no row matched; the caller
     *     re-reads and branches
     */
    Registration transition(Registration registration, RegistrationStatus from);
}
