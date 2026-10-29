package com.unihub.notification.application.port.out;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence of {@code notifications} (Tier B transaction script, backend-coding-standards.md
 * §9.1). Every state change is one conditional statement, so a row only ever moves {@code PENDING →
 * SENT | FAILED} and never back, whatever order concurrent deliveries run in.
 */
public interface NotificationRepository {

    /**
     * Inserts the rows with {@code ON CONFLICT (source_event_id, user_id, channel) DO NOTHING}: a
     * redelivery or a double publish inserts nothing new.
     *
     * @return how many rows were actually inserted
     */
    int insertIfAbsent(List<NewNotification> rows);

    /** The event's rows still {@code PENDING}, in a fixed order (by id). */
    List<PendingNotification> findPending(UUID sourceEventId);

    int countPending(UUID sourceEventId);

    /**
     * Takes the send lease of a row: one atomic {@code UPDATE … SET claimed_at = now() WHERE status
     * = 'PENDING' AND (claimed_at IS NULL OR claimed_at < now() - lease)}. Empty when the row is no
     * longer {@code PENDING} or another delivery holds a live claim.
     *
     * @return the claim token, to be passed back to {@link #release} exactly as given
     */
    Optional<Instant> claim(UUID notificationId, Duration lease);

    /**
     * Gives the claim back so the next retry hop can take the row at once. A no-op unless the row's
     * claim is still this token, so an overrunning sender never clears a claim another delivery has
     * since taken.
     */
    void release(UUID notificationId, Instant claimToken);

    /** {@code PENDING → SENT}, {@code sent_at} set. A no-op on any other status. */
    void markSent(UUID notificationId);

    /** {@code PENDING → FAILED}. A no-op on any other status. */
    void markFailed(UUID notificationId);

    /** Marks every {@code PENDING} row of the event {@code FAILED} (a superseded reschedule). */
    int failPending(UUID sourceEventId);

    /** When the oldest {@code PENDING} row was created; empty when none is waiting. */
    Optional<Instant> oldestPendingCreatedAt();
}
