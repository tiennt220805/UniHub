package com.unihub.registration.application.usecase;

import com.unihub.registration.application.command.ExpireSeatHoldCommand;
import com.unihub.registration.application.port.in.ExpireSeatHoldUseCase;
import com.unihub.registration.application.port.out.SeatHoldExpiryMetrics;
import com.unihub.registration.application.port.out.SeatLock;
import com.unihub.registration.domain.exception.SeatLockUnavailableException;
import com.unihub.shared.domain.OptimisticConflictException;
import org.springframework.stereotype.Service;

/**
 * Entry point of the seat-hold expiry — deliberately NOT {@code @Transactional}, for the same
 * reason as {@code ReserveSeat}: the seat lock is taken before the transaction opens and released
 * after it ends (backend-coding-standards.md §19.2), and the transaction is a separate bean so the
 * call crosses the Spring proxy (§19.3). Returning a seat is as much part of the anti-overbooking
 * critical section as taking one (registration.md §3), so it takes the same lock and, like a claim,
 * never proceeds without it (INV-08).
 *
 * <p>A lost {@code version} race (an organizer edit, which is outside the seat lock, or the payment
 * confirmation winning the registration) is retried in a new transaction, still inside the lock:
 * the re-read then finds the other outcome, and a lost race is not an error (checklist C5). Only
 * when every attempt loses does the exception reach the shared error handler, which retries the
 * message.
 */
@Service
class ExpireSeatHold implements ExpireSeatHoldUseCase {
    private final SeatLock seatLock;
    private final ExpireSeatHoldTransaction transaction;
    private final SeatHoldExpiryMetrics metrics;
    private final RegistrationPolicy policy;

    ExpireSeatHold(
            SeatLock seatLock,
            ExpireSeatHoldTransaction transaction,
            SeatHoldExpiryMetrics metrics,
            RegistrationPolicy policy) {
        this.seatLock = seatLock;
        this.transaction = transaction;
        this.metrics = metrics;
        this.policy = policy;
    }

    @Override
    public void expire(ExpireSeatHoldCommand command) {
        // lock → transaction opens → commit or rollback → lock released
        try (var held = acquire(command)) {
            for (int attempt = 1; ; attempt++) {
                try {
                    transaction.attempt(command);
                    return;
                } catch (OptimisticConflictException lostRace) {
                    if (attempt >= policy.seatClaimAttempts()) {
                        throw lostRace;
                    }
                }
            }
        }
    }

    /** The failure that is never parked (checklist D19): counted, then rethrown unchanged. */
    private com.unihub.registration.application.port.out.SeatLockHandle acquire(
            ExpireSeatHoldCommand command) {
        try {
            return seatLock.tryAcquire(command.workshopId(), policy.lockWait(), policy.lockLease());
        } catch (SeatLockUnavailableException unavailable) {
            metrics.lockUnavailable();
            throw unavailable;
        }
    }
}
