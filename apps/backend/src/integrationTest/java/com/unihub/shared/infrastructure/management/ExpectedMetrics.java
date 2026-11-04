package com.unihub.shared.infrastructure.management;

import java.util.List;

/**
 * Every application-side series of the metrics catalog (observability.md §3, §4), in the names
 * Prometheus renders. One list, so the export test (4.4) and the dashboard/alert provisioning test
 * (6.4) cannot drift apart. The broker's own {@code rabbitmq_queue_*} series are not here: they
 * come from {@code rabbitmq_prometheus} on port 15692, not from this application's scrape.
 *
 * <p>{@code unihub_notification_delivery_total{channel,result}} is not listed either: a counter
 * with labels only exists once a (channel, result) pair has happened, and no rule alerts on its
 * absence.
 */
final class ExpectedMetrics {

    /** §3, the four golden signals. */
    static final List<String> GOLDEN_SIGNALS =
            List.of(
                    "http_server_requests_seconds_bucket",
                    "http_server_requests_seconds_count",
                    "hikaricp_connections_active",
                    "hikaricp_connections_max",
                    "hikaricp_connections_pending",
                    "jvm_memory_used_bytes",
                    "jvm_memory_max_bytes",
                    "system_cpu_usage",
                    "process_cpu_usage");

    /** Counters that an {@code increase() > 0} rule watches: they must exist at 0 from start. */
    static final List<String> ALERTED_COUNTERS =
            List.of(
                    "unihub_audit_publish_failures_total{reason=\"dropped\"}",
                    "unihub_audit_publish_failures_total{reason=\"nack\"}",
                    "unihub_audit_publish_failures_total{reason=\"timeout\"}",
                    "unihub_audit_publish_failures_total{reason=\"error\"}",
                    "unihub_payment_reconciliation_stuck_total",
                    "unihub_payment_refund_parked_total",
                    "unihub_seat_hold_expiry_lock_unavailable_total");

    /** §4, everything else the application exports. */
    static final List<String> BUSINESS =
            List.of(
                    "unihub_registration_active_holds",
                    "unihub_outbox_oldest_pending_age_seconds",
                    "unihub_postgres_replica_lag_seconds",
                    "unihub_legacy_sync_last_run_quarantine_ratio",
                    "unihub_dependency_up{dependency=\"redis\"}",
                    "unihub_dependency_up{dependency=\"rabbitmq\"}",
                    "resilience4j_circuitbreaker_state{name=\"paymentGateway\"",
                    "unihub_seat_claim_version_conflicts_total",
                    "unihub_seat_claim_retries_exhausted_total",
                    "unihub_workshop_edit_conflicts_total",
                    "unihub_payment_refund_required_oldest_age_seconds",
                    "unihub_notification_pending_oldest_age_seconds",
                    "unihub_checkin_scan_clock_ahead_total",
                    "unihub_checkin_scan_resubmission_conflicts_total");

    static List<String> all() {
        var all = new java.util.ArrayList<String>(GOLDEN_SIGNALS);
        all.addAll(ALERTED_COUNTERS);
        all.addAll(BUSINESS);
        return all;
    }

    private ExpectedMetrics() {}
}
