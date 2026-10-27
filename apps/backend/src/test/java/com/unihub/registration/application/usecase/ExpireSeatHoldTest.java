package com.unihub.registration.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.unihub.registration.application.command.ExpireSeatHoldCommand;
import com.unihub.registration.application.port.out.SeatHoldExpiryMetrics;
import com.unihub.registration.application.port.out.SeatLock;
import com.unihub.registration.application.port.out.SeatLockHandle;
import com.unihub.registration.domain.exception.HoldNotYetExpiredException;
import com.unihub.registration.domain.exception.SeatLockUnavailableException;
import com.unihub.shared.domain.OptimisticConflictException;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.WorkshopId;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The lock → transaction → unlock shape of the hold expiry and its retry on a lost version race
 * (registration.md §3, checklist D19, C5).
 */
class ExpireSeatHoldTest {
    private static final RegistrationPolicy POLICY =
            new RegistrationPolicy(
                    Duration.ofMinutes(15), Duration.ofSeconds(2), Duration.ofSeconds(5), 3);
    private static final WorkshopId WORKSHOP = WorkshopId.newId();
    private static final ExpireSeatHoldCommand COMMAND =
            new ExpireSeatHoldCommand(RegistrationId.newId(), WORKSHOP);

    private final List<String> events = new ArrayList<>();
    private final SeatLock seatLock = mock(SeatLock.class);
    private final ExpireSeatHoldTransaction transaction = mock(ExpireSeatHoldTransaction.class);
    private final SeatHoldExpiryMetrics metrics = mock(SeatHoldExpiryMetrics.class);
    private final ExpireSeatHold expire =
            new ExpireSeatHold(seatLock, transaction, metrics, POLICY);

    private void lockGranted() {
        SeatLockHandle handle = () -> events.add("unlock");
        when(seatLock.tryAcquire(WORKSHOP, POLICY.lockWait(), POLICY.lockLease()))
                .thenAnswer(
                        invocation -> {
                            events.add("lock");
                            return handle;
                        });
    }

    private static OptimisticConflictException lostRace() {
        return new OptimisticConflictException("WORKSHOP", WORKSHOP.value());
    }

    @Test
    @DisplayName("the lock is taken before the transaction opens and released after it ends")
    void lockThenTransactionThenUnlock() {
        lockGranted();
        doAnswer(
                        invocation -> {
                            events.add("transaction");
                            return ExpireSeatHoldTransaction.Effect.EXPIRED;
                        })
                .when(transaction)
                .attempt(COMMAND);

        expire.expire(COMMAND);

        assertThat(events).containsExactly("lock", "transaction", "unlock");
        verifyNoInteractions(metrics);
    }

    @Test
    @DisplayName("a lost version race is retried in a new transaction, still inside the lock")
    void lostRace_isRetriedInsideTheLock() {
        lockGranted();
        when(transaction.attempt(COMMAND))
                .thenThrow(lostRace())
                .thenReturn(ExpireSeatHoldTransaction.Effect.EXPIRED);

        expire.expire(COMMAND);

        verify(transaction, times(2)).attempt(COMMAND);
        assertThat(events).containsExactly("lock", "unlock");
    }

    @Test
    @DisplayName("three lost races in a row surface the conflict to the retry chain; lock released")
    void everyAttemptLost_rethrows() {
        lockGranted();
        when(transaction.attempt(COMMAND)).thenThrow(lostRace());

        assertThatThrownBy(() -> expire.expire(COMMAND))
                .isInstanceOf(OptimisticConflictException.class);

        verify(transaction, times(3)).attempt(COMMAND);
        assertThat(events).containsExactly("lock", "unlock");
    }

    @Test
    @DisplayName("no lock, no transaction: counted and rethrown so the chain keeps the message")
    void lockUnavailable_isCountedAndRethrown() {
        when(seatLock.tryAcquire(WORKSHOP, POLICY.lockWait(), POLICY.lockLease()))
                .thenThrow(new SeatLockUnavailableException(WORKSHOP));

        assertThatThrownBy(() -> expire.expire(COMMAND))
                .isInstanceOf(SeatLockUnavailableException.class);

        verify(metrics).lockUnavailable();
        verifyNoInteractions(transaction);
    }

    @Test
    @DisplayName("a failure inside the transaction releases the lock and is not counted as one")
    void transactionFailure_releasesTheLock() {
        lockGranted();
        doThrow(new HoldNotYetExpiredException(COMMAND.registrationId(), Instant.now()))
                .when(transaction)
                .attempt(any());

        assertThatThrownBy(() -> expire.expire(COMMAND))
                .isInstanceOf(HoldNotYetExpiredException.class);

        assertThat(events).containsExactly("lock", "unlock");
        verify(metrics, never()).lockUnavailable();
        verify(transaction, times(1)).attempt(COMMAND);
    }
}
