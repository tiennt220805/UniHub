package com.unihub.registration.application.port.in;

import com.unihub.registration.application.command.ExpireSeatHoldCommand;

public interface ExpireSeatHoldUseCase {

    /**
     * Idempotent under redelivery: a registration that is no longer {@code RESERVED} is left alone,
     * so the seat is returned at most once however often the message arrives.
     *
     * @throws com.unihub.registration.domain.exception.SeatLockUnavailableException when the seat
     *     lock cannot be taken (contention, Redis unreachable or hung): transient, and never parked
     *     (checklist D19)
     * @throws com.unihub.registration.domain.exception.HoldNotYetExpiredException when the message
     *     arrived before {@code hold_expires_at} (checklist D27)
     * @throws com.unihub.shared.domain.OptimisticConflictException when every attempt lost the
     *     {@code version} race (checklist C5)
     */
    void expire(ExpireSeatHoldCommand command);
}
