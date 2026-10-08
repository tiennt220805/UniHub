package com.unihub.workshop.application.port.out;

import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.domain.model.Workshop;
import java.util.Optional;

/** Write-side persistence of the Workshop aggregate (backend-coding-standards.md §6.3, §9.2). */
public interface WorkshopRepository {

    /** Absent is an empty Optional, never an exception; the use case decides what that means. */
    Optional<Workshop> findById(WorkshopId id);

    /**
     * Inserts a new workshop or updates an existing one, and returns the state as persisted (the
     * version moves on every update).
     *
     * @throws com.unihub.shared.domain.OptimisticConflictException when the stored version is no
     *     longer the one the aggregate was loaded with
     */
    Workshop save(Workshop workshop);
}
