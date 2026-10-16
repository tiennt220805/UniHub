package com.unihub.shared.infrastructure.out.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.shared.infrastructure.RolledBackJdbcSupport;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M-21's database half: the sweep deletes {@code COMPLETED} keys strictly older than the cutoff and
 * nothing else. The use case's cutoff arithmetic is unit-tested with a fixed clock; here the
 * boundary is exercised against the real table, to the microsecond.
 */
class JdbcIdempotencyKeyCleanupIT extends RolledBackJdbcSupport {

    private static final Instant CUTOFF = Instant.parse("2026-10-16T09:30:00Z");

    private JdbcIdempotencyKeyCleanup cleanup() {
        return new JdbcIdempotencyKeyCleanup(jdbc);
    }

    @Test
    @DisplayName("a key completed before the cutoff is deleted; at the cutoff or later, it is kept")
    void boundary_isStrictlyBeforeTheCutoff() {
        var before = completedAt(CUTOFF.minusNanos(1_000));
        var atCutoff = completedAt(CUTOFF);
        var after = completedAt(CUTOFF.plusNanos(1_000));

        int deleted = cleanup().deleteCompletedBefore(CUTOFF);

        assertThat(deleted).isEqualTo(1);
        assertThat(exists(before)).as("1µs before the cutoff").isFalse();
        assertThat(exists(atCutoff)).as("exactly at the cutoff").isTrue();
        assertThat(exists(after)).as("1µs after the cutoff").isTrue();
    }

    @Test
    @DisplayName("an IN_PROGRESS key is never deleted, however old")
    void inProgress_isNeverDeleted() {
        var open = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO idempotency_keys (idempotency_key, scope, actor_user_id,"
                        + " request_fingerprint, status, resource_id, created_at)"
                        + " VALUES (?, 'PAYMENT_CHARGE', ?, ?, 'IN_PROGRESS', ?, ?)",
                open,
                UUID.randomUUID(),
                "a".repeat(64),
                UUID.randomUUID(),
                OffsetDateTime.ofInstant(CUTOFF.minusSeconds(86_400L * 365), ZoneOffset.UTC));

        assertThat(cleanup().deleteCompletedBefore(CUTOFF)).isZero();

        assertThat(exists(open)).isTrue();
    }

    @Test
    @DisplayName("a second run right after the first finds nothing left to delete")
    void secondRun_isANoOp() {
        completedAt(CUTOFF.minusSeconds(60));

        assertThat(cleanup().deleteCompletedBefore(CUTOFF)).isEqualTo(1);
        assertThat(cleanup().deleteCompletedBefore(CUTOFF)).isZero();
    }

    private String completedAt(Instant completedAt) {
        var key = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO idempotency_keys (idempotency_key, scope, actor_user_id,"
                        + " request_fingerprint, status, result_type, result_body, completed_at)"
                        + " VALUES (?, 'REGISTRATION_CREATE', ?, ?, 'COMPLETED', 'Confirmed',"
                        + " '{}'::jsonb, ?)",
                key,
                UUID.randomUUID(),
                "a".repeat(64),
                OffsetDateTime.ofInstant(completedAt, ZoneOffset.UTC));
        return key;
    }

    private boolean exists(String key) {
        return jdbc.queryForObject(
                        "SELECT count(*) FROM idempotency_keys WHERE idempotency_key = ?",
                        Integer.class,
                        key)
                == 1;
    }
}
