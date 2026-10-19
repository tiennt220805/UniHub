package com.unihub.shared.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unihub.shared.application.port.out.EventPublicationCleanup;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PurgeEventPublicationsTest {

    private static final Instant NOW = Instant.parse("2026-10-16T10:00:00Z");

    private final List<Instant> cutoffs = new ArrayList<>();
    private final EventPublicationCleanup cleanup =
            cutoff -> {
                cutoffs.add(cutoff);
                return 4;
            };
    private final PurgeEventPublications purge =
            new PurgeEventPublications(cleanup, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("deletes rows published before now minus the retention, from the injected clock")
    void cutoff_isNowMinusRetention() {
        int deleted = purge.purge(Duration.ofHours(1));

        assertThat(cutoffs).containsExactly(Instant.parse("2026-10-16T09:00:00Z"));
        assertThat(deleted).isEqualTo(4);
    }

    @Test
    @DisplayName("a zero or negative retention is refused, so a config slip cannot wipe the outbox")
    void nonPositiveRetention_isRefused() {
        assertThatThrownBy(() -> purge.purge(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> purge.purge(Duration.ofMinutes(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(cutoffs).isEmpty();
    }
}
