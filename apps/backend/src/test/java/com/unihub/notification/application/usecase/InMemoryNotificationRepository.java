package com.unihub.notification.application.usecase;

import com.unihub.notification.application.port.out.NewNotification;
import com.unihub.notification.application.port.out.NotificationRepository;
import com.unihub.notification.application.port.out.PendingNotification;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The semantics of the real repository (conditional updates, the unique key, the lease), in memory,
 * on a clock the test can move. The real one is proved against Postgres in {@code
 * JdbcNotificationRepositoryIT}; this lets the use case's logic be checked fast.
 */
final class InMemoryNotificationRepository implements NotificationRepository {

    static final class Row {
        final UUID id = UUID.randomUUID();
        final NewNotification data;
        String status = "PENDING";
        Instant claimedAt;

        Row(NewNotification data) {
            this.data = data;
        }
    }

    final List<Row> rows = new ArrayList<>();
    private final Clock clock;

    InMemoryNotificationRepository(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized int insertIfAbsent(List<NewNotification> toInsert) {
        var inserted = 0;
        for (var data : toInsert) {
            var exists =
                    rows.stream()
                            .anyMatch(
                                    r ->
                                            r.data.sourceEventId().equals(data.sourceEventId())
                                                    && r.data.userId().equals(data.userId())
                                                    && r.data.channel().equals(data.channel()));
            if (!exists) {
                rows.add(new Row(data));
                inserted++;
            }
        }
        return inserted;
    }

    @Override
    public synchronized List<PendingNotification> findPending(UUID sourceEventId) {
        return rows.stream()
                .filter(r -> r.data.sourceEventId().equals(sourceEventId))
                .filter(r -> r.status.equals("PENDING"))
                .map(
                        r ->
                                new PendingNotification(
                                        r.id, r.data.userId(), r.data.channel(), r.data.type()))
                .toList();
    }

    @Override
    public synchronized int countPending(UUID sourceEventId) {
        return findPending(sourceEventId).size();
    }

    @Override
    public synchronized Optional<Instant> claim(UUID notificationId, Duration lease) {
        var row = byId(notificationId);
        var now = clock.instant();
        if (row.status.equals("PENDING")
                && (row.claimedAt == null || row.claimedAt.isBefore(now.minus(lease)))) {
            row.claimedAt = now;
            return Optional.of(now);
        }
        return Optional.empty();
    }

    @Override
    public synchronized void release(UUID notificationId, Instant claimToken) {
        var row = byId(notificationId);
        if (row.status.equals("PENDING") && claimToken.equals(row.claimedAt)) {
            row.claimedAt = null;
        }
    }

    @Override
    public synchronized void markSent(UUID notificationId) {
        var row = byId(notificationId);
        if (row.status.equals("PENDING")) {
            row.status = "SENT";
        }
    }

    @Override
    public synchronized void markFailed(UUID notificationId) {
        var row = byId(notificationId);
        if (row.status.equals("PENDING")) {
            row.status = "FAILED";
        }
    }

    @Override
    public synchronized int failPending(UUID sourceEventId) {
        var failed = 0;
        for (var row : rows) {
            if (row.data.sourceEventId().equals(sourceEventId) && row.status.equals("PENDING")) {
                row.status = "FAILED";
                failed++;
            }
        }
        return failed;
    }

    @Override
    public synchronized Optional<Instant> oldestPendingCreatedAt() {
        return rows.stream().anyMatch(r -> r.status.equals("PENDING"))
                ? Optional.of(clock.instant())
                : Optional.empty();
    }

    synchronized List<Row> rowsOf(UUID userId, String channel) {
        return rows.stream()
                .filter(r -> r.data.userId().equals(userId) && r.data.channel().equals(channel))
                .toList();
    }

    synchronized long count(String status) {
        return rows.stream().filter(r -> r.status.equals(status)).count();
    }

    private Row byId(UUID id) {
        return rows.stream().filter(r -> r.id.equals(id)).findFirst().orElseThrow();
    }
}
