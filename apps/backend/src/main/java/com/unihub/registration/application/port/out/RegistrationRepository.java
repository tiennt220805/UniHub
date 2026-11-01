package com.unihub.registration.application.port.out;

import com.unihub.registration.domain.model.Registration;
import com.unihub.registration.domain.model.RegistrationStatus;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.domain.id.WorkshopId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RegistrationRepository {

    /** Absent is an empty Optional; the use case decides what that means. */
    Optional<Registration> findById(RegistrationId id);

    /**
     * The student's own registrations, newest first. Read at the Master by the use cases that call
     * it: a student looks at the list right after registering.
     *
     * @param status only this status, or null for all
     */
    RegistrationPage findByUser(UserId userId, RegistrationStatus status, int page, int size);

    /**
     * The workshop's {@code RESERVED} and {@code CONFIRMED} registrations, oldest first (the id
     * breaks ties), so a fan-out walks them in the same order on every retry. Read at the Master by
     * the use case that calls it.
     */
    List<Registration> findHoldingSeats(WorkshopId workshopId);

    /**
     * The registrations that hold one of these ticket codes, in any status. A code with no
     * registration yields nothing. Read at the Master by the use case that calls it.
     */
    List<Registration> findByTicketCodes(Collection<String> ticketCodes);

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
