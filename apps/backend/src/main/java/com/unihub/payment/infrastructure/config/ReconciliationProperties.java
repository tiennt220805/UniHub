package com.unihub.payment.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code unihub.payment.reconciliation.*} (payment.md §6). {@code stale-after} is how long a
 * payment may stay {@code PENDING} before the sweep takes it, {@code batch-size} how many one sweep
 * takes, {@code stuck-after} how long the gateway may keep a payment "processing" before an
 * operator is told.
 */
@ConfigurationProperties(prefix = "unihub.payment.reconciliation")
record ReconciliationProperties(Duration staleAfter, int batchSize, Duration stuckAfter) {}
