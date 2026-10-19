package com.unihub.shared.infrastructure.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.shared.infrastructure.RolledBackJdbcSupport;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M-25's cleanup half: the sweep deletes {@code PUBLISHED} outbox rows strictly older than the
 * cutoff and nothing else. The use case's cutoff arithmetic is unit-tested with a fixed clock; the
 * boundary is exercised here against the real table, to the microsecond.
 */
class JdbcEventPublicationCleanupIT extends RolledBackJdbcSupport {

    private static final Instant CUTOFF = Instant.parse("2026-10-16T09:00:00Z");

    private JdbcEventPublicationCleanup cleanup() {
        return new JdbcEventPublicationCleanup(jdbc);
    }

    @Test
    @DisplayName("a row published before the cutoff is deleted; at the cutoff or later, it is kept")
    void boundary_isStrictlyBeforeTheCutoff() {
        var before = published(CUTOFF.minusNanos(1_000));
        var atCutoff = published(CUTOFF);
        var after = published(CUTOFF.plusNanos(1_000));

        int deleted = cleanup().deletePublishedBefore(CUTOFF);

        assertThat(deleted).isEqualTo(1);
        assertThat(exists(before)).as("1µs before the cutoff").isFalse();
        assertThat(exists(atCutoff)).as("exactly at the cutoff").isTrue();
        assertThat(exists(after)).as("1µs after the cutoff").isTrue();
    }

    @Test
    @DisplayName("a PENDING row is never deleted, however old: it has not reached the broker")
    void pending_isNeverDeleted() {
        var pending = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO event_publications (id, aggregate_type, aggregate_id, event_type,"
                        + " routing_key, payload, created_at)"
                        + " VALUES (?, 'PAYMENT', ?, 'Sample', 'k', '{}'::jsonb, ?)",
                pending,
                UUID.randomUUID(),
                OffsetDateTime.ofInstant(CUTOFF.minusSeconds(86_400L * 365), ZoneOffset.UTC));

        assertThat(cleanup().deletePublishedBefore(CUTOFF)).isZero();

        assertThat(exists(pending)).isTrue();
    }

    @Test
    @DisplayName("a second run right after the first finds nothing left to delete")
    void secondRun_isANoOp() {
        published(CUTOFF.minusSeconds(60));

        assertThat(cleanup().deletePublishedBefore(CUTOFF)).isEqualTo(1);
        assertThat(cleanup().deletePublishedBefore(CUTOFF)).isZero();
    }

    private UUID published(Instant publishedAt) {
        var id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO event_publications (id, aggregate_type, aggregate_id, event_type,"
                        + " routing_key, payload, status, published_at)"
                        + " VALUES (?, 'PAYMENT', ?, 'Sample', 'k', '{}'::jsonb, 'PUBLISHED', ?)",
                id,
                UUID.randomUUID(),
                OffsetDateTime.ofInstant(publishedAt, ZoneOffset.UTC));
        return id;
    }

    private boolean exists(UUID id) {
        return jdbc.queryForObject(
                        "SELECT count(*) FROM event_publications WHERE id = ?", Integer.class, id)
                == 1;
    }
}
