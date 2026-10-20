package com.unihub.registration.application.usecase;

import com.unihub.registration.application.command.ReserveSeatCommand;
import com.unihub.registration.application.port.in.ReserveSeatUseCase;
import com.unihub.registration.application.port.out.SeatClaimMetrics;
import com.unihub.registration.application.port.out.SeatLock;
import com.unihub.registration.application.result.ReserveSeatResult;
import com.unihub.registration.domain.exception.SeatTemporarilyUnavailableException;
import com.unihub.shared.application.port.out.IdempotencyScope;
import com.unihub.shared.application.port.out.IdempotencyStore;
import com.unihub.shared.application.usecase.IdempotentReplay;
import com.unihub.shared.domain.OptimisticConflictException;
import org.springframework.stereotype.Service;

/**
 * Entry point of the seat reservation — deliberately NOT {@code @Transactional}: the lock is taken
 * before the transaction opens and released after it ends (backend-coding-standards.md §19.2), and
 * the transaction lives in a separate bean so the call crosses the Spring proxy (§19.3).
 *
 * <p>A retried request is answered from the recorded Result before the lock is even asked for
 * (registration.md §4: a replay never needs Redis). A transient failure — lock busy, retries
 * exhausted — is an exception that rolls the key row back with it, so a retry runs for real.
 *
 * <p>A claim that loses the {@code workshops.version} race (an organizer edit, or another claim
 * through an expired lease) is retried in a new transaction, still inside the lock; only when every
 * attempt loses is it a {@code 503}, never a {@code 409} (Phase 2 checklist D3).
 */
@Service
class ReserveSeat implements ReserveSeatUseCase {
    private final IdempotencyStore idempotency;
    private final SeatLock seatLock;
    private final ReserveSeatTransaction transaction;
    private final SeatClaimMetrics metrics;
    private final RegistrationPolicy policy;

    ReserveSeat(
            IdempotencyStore idempotency,
            SeatLock seatLock,
            ReserveSeatTransaction transaction,
            SeatClaimMetrics metrics,
            RegistrationPolicy policy) {
        this.idempotency = idempotency;
        this.seatLock = seatLock;
        this.transaction = transaction;
        this.metrics = metrics;
        this.policy = policy;
    }

    @Override
    public ReserveSeatResult reserve(ReserveSeatCommand command) {
        var replay =
                IdempotentReplay.of(
                        idempotency,
                        IdempotencyScope.REGISTRATION_CREATE,
                        command.actor().userId(),
                        command.idempotencyKey(),
                        command.requestFingerprint(),
                        ReserveSeatResult.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        // lock → transaction opens → commit or rollback → lock released
        try (var held =
                seatLock.tryAcquire(command.workshopId(), policy.lockWait(), policy.lockLease())) {
            for (int attempt = 1; ; attempt++) {
                try {
                    return transaction.execute(command);
                } catch (OptimisticConflictException lostRace) {
                    metrics.versionConflicted();
                    if (attempt >= policy.seatClaimAttempts()) {
                        metrics.retriesExhausted();
                        throw new SeatTemporarilyUnavailableException(
                                command.workshopId(), lostRace);
                    }
                }
            }
        }
    }
}
