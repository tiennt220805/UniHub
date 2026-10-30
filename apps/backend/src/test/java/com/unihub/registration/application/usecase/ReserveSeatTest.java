package com.unihub.registration.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.unihub.registration.application.command.ReserveSeatCommand;
import com.unihub.registration.application.port.out.SeatClaimMetrics;
import com.unihub.registration.application.port.out.SeatLock;
import com.unihub.registration.application.port.out.SeatLockHandle;
import com.unihub.registration.application.result.ReserveSeatResult;
import com.unihub.registration.domain.exception.SeatLockUnavailableException;
import com.unihub.registration.domain.exception.SeatTemporarilyUnavailableException;
import com.unihub.shared.application.port.out.IdempotencyKeyInUseException;
import com.unihub.shared.application.port.out.IdempotencyKeyReusedException;
import com.unihub.shared.application.port.out.IdempotencyRecord;
import com.unihub.shared.application.port.out.IdempotencyScope;
import com.unihub.shared.application.port.out.IdempotencyStore;
import com.unihub.shared.application.port.out.StoredResult;
import com.unihub.shared.domain.OptimisticConflictException;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.security.Actor;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The lock → transaction → unlock shape and the seat-claim retry (D3, D4, backend §25.1). */
class ReserveSeatTest {
    private static final RegistrationPolicy POLICY =
            new RegistrationPolicy(
                    Duration.ofMinutes(15), Duration.ofSeconds(2), Duration.ofSeconds(5), 3);
    private static final WorkshopId WORKSHOP = WorkshopId.newId();
    private static final ReserveSeatCommand COMMAND =
            new ReserveSeatCommand(
                    new Actor.UserActor(UserId.newId(), Set.of()),
                    WORKSHOP,
                    UUID.randomUUID().toString(),
                    "f".repeat(64));

    private final List<String> events = new ArrayList<>();
    private final IdempotencyStore idempotency = mock(IdempotencyStore.class);
    private final SeatLock seatLock = mock(SeatLock.class);
    private final ReserveSeatTransaction transaction = mock(ReserveSeatTransaction.class);
    private final SeatClaimMetrics metrics = mock(SeatClaimMetrics.class);
    private final ReserveSeat reserveSeat =
            new ReserveSeat(idempotency, seatLock, transaction, metrics, POLICY);

    private void lockGranted() {
        SeatLockHandle handle = () -> events.add("unlock");
        when(seatLock.tryAcquire(WORKSHOP, POLICY.lockWait(), POLICY.lockLease()))
                .thenAnswer(
                        invocation -> {
                            events.add("lock");
                            return handle;
                        });
    }

    private static ReserveSeatResult.Confirmed confirmed() {
        var now = Instant.parse("2026-12-01T09:00:00Z");
        return new ReserveSeatResult.Confirmed(RegistrationId.newId(), now, now, "CODE", "h.p.s");
    }

    @Test
    @DisplayName("the lock is taken before the transaction and released only after it returns")
    void lockWrapsTheTransaction() {
        lockGranted();
        var result = confirmed();
        when(transaction.execute(COMMAND))
                .thenAnswer(
                        invocation -> {
                            events.add("transaction");
                            return result;
                        });

        assertThat(reserveSeat.reserve(COMMAND)).isSameAs(result);

        assertThat(events).containsExactly("lock", "transaction", "unlock");
        verify(metrics, never()).versionConflicted();
    }

    @Test
    @DisplayName("the lock is released when the transaction throws")
    void lockReleasedOnException() {
        lockGranted();
        when(transaction.execute(COMMAND)).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> reserveSeat.reserve(COMMAND))
                .isInstanceOf(IllegalStateException.class);

        assertThat(events).containsExactly("lock", "unlock");
    }

    @Test
    @DisplayName("a lock that cannot be taken stops the request before any transaction")
    void lockUnavailable_runsNoTransaction() {
        when(seatLock.tryAcquire(WORKSHOP, POLICY.lockWait(), POLICY.lockLease()))
                .thenThrow(new SeatLockUnavailableException(WORKSHOP));

        assertThatThrownBy(() -> reserveSeat.reserve(COMMAND))
                .isInstanceOf(SeatLockUnavailableException.class);

        verify(transaction, never()).execute(any());
    }

    @Test
    @DisplayName("a lost version race is retried in a new transaction, inside the same lock")
    void lostRace_retriesAndSucceeds() {
        lockGranted();
        var result = confirmed();
        when(transaction.execute(COMMAND))
                .thenThrow(new OptimisticConflictException("WORKSHOP", WORKSHOP.value()))
                .thenThrow(new OptimisticConflictException("WORKSHOP", WORKSHOP.value()))
                .thenReturn(result);

        assertThat(reserveSeat.reserve(COMMAND)).isSameAs(result);

        verify(transaction, times(3)).execute(COMMAND);
        verify(metrics, times(2)).versionConflicted();
        verify(metrics, never()).retriesExhausted();
        assertThat(events).containsExactly("lock", "unlock");
    }

    @Test
    @DisplayName("when every attempt loses the race the answer is 503, never a conflict")
    void retriesExhausted_isTemporarilyUnavailable() {
        lockGranted();
        var lost = new OptimisticConflictException("WORKSHOP", WORKSHOP.value());
        when(transaction.execute(COMMAND)).thenThrow(lost);

        assertThatThrownBy(() -> reserveSeat.reserve(COMMAND))
                .isInstanceOf(SeatTemporarilyUnavailableException.class)
                .hasCause(lost);

        verify(transaction, times(3)).execute(COMMAND);
        verify(metrics, times(3)).versionConflicted();
        verify(metrics).retriesExhausted();
        assertThat(events).containsExactly("lock", "unlock");
    }

    @Test
    @DisplayName("a business rejection is a result, not a retry")
    void soldOut_isNotRetried() {
        lockGranted();
        when(transaction.execute(COMMAND)).thenReturn(new ReserveSeatResult.SoldOut());

        assertThat(reserveSeat.reserve(COMMAND)).isEqualTo(new ReserveSeatResult.SoldOut());

        verify(transaction).execute(COMMAND);
    }

    @Test
    @DisplayName("a policy needs at least one attempt")
    void policy_rejectsZeroAttempts() {
        assertThatThrownBy(
                        () ->
                                new RegistrationPolicy(
                                        Duration.ofMinutes(15),
                                        Duration.ofSeconds(2),
                                        Duration.ofSeconds(5),
                                        0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- idempotency: the fast replay path ----------

    private void recorded(IdempotencyRecord record) {
        when(idempotency.find(
                        IdempotencyScope.REGISTRATION_CREATE,
                        COMMAND.actor().userId(),
                        COMMAND.idempotencyKey()))
                .thenReturn(Optional.of(record));
    }

    @Test
    @DisplayName("a recorded Result is replayed without taking the lock or opening a transaction")
    void completedKey_replaysWithoutLockOrTransaction() {
        var stored = new StoredResult("SoldOut", "{}");
        recorded(new IdempotencyRecord.Completed(COMMAND.requestFingerprint(), stored));
        when(idempotency.decode(stored, ReserveSeatResult.class))
                .thenReturn(new ReserveSeatResult.SoldOut());

        assertThat(reserveSeat.reserve(COMMAND)).isEqualTo(new ReserveSeatResult.SoldOut());

        verify(seatLock, never()).tryAcquire(any(), any(), any());
        verify(transaction, never()).execute(any());
    }

    @Test
    @DisplayName("the same key with another body is refused before any lock or transaction")
    void differentFingerprint_isRefused() {
        recorded(
                new IdempotencyRecord.Completed("0".repeat(64), new StoredResult("SoldOut", "{}")));

        assertThatThrownBy(() -> reserveSeat.reserve(COMMAND))
                .isInstanceOf(IdempotencyKeyReusedException.class);

        verify(seatLock, never()).tryAcquire(any(), any(), any());
        verify(transaction, never()).execute(any());
    }

    @Test
    @DisplayName("a key still being processed is IdempotencyKeyInUse, with no lock or transaction")
    void inProgressKey_isInUse() {
        recorded(new IdempotencyRecord.InProgress(COMMAND.requestFingerprint()));

        assertThatThrownBy(() -> reserveSeat.reserve(COMMAND))
                .isInstanceOf(IdempotencyKeyInUseException.class);

        verify(seatLock, never()).tryAcquire(any(), any(), any());
    }
}
