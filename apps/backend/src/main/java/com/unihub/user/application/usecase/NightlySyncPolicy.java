package com.unihub.user.application.usecase;

import java.time.ZoneId;
import java.util.Objects;

/**
 * Tuning for the nightly sync (user.md §7), built from configuration in {@code
 * infrastructure/config}.
 *
 * @param zone the zone whose calendar date names tonight's export file (user.md §2, §7)
 * @param batchSize rows per committed batch in Pass 2
 */
public record NightlySyncPolicy(ZoneId zone, int batchSize) {

    public NightlySyncPolicy {
        Objects.requireNonNull(zone, "zone");
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1, was " + batchSize);
        }
    }
}
