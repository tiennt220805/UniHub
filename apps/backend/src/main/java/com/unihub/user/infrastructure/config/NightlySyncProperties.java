package com.unihub.user.infrastructure.config;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code unihub.jobs.nightly-sync.*} (values: user.md §2, §7).
 *
 * @param enabled whether the scheduled trigger exists at all
 * @param cron when the trigger fires, read in {@code zone}
 * @param zone the named zone of both the schedule and the export date
 * @param inboundDir where the legacy system drops the export
 * @param batchSize rows per committed batch
 */
@ConfigurationProperties(prefix = "unihub.jobs.nightly-sync")
public record NightlySyncProperties(
        boolean enabled, String cron, ZoneId zone, Path inboundDir, int batchSize) {

    public NightlySyncProperties {
        Objects.requireNonNull(cron, "unihub.jobs.nightly-sync.cron");
        Objects.requireNonNull(zone, "unihub.jobs.nightly-sync.zone");
        Objects.requireNonNull(inboundDir, "unihub.jobs.nightly-sync.inbound-dir");
    }
}
