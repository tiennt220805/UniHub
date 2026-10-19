package com.unihub.shared.application.port.out;

/**
 * Records who did what to which target (architecture.md §5.5, backend-coding-standards.md §12).
 * Called explicitly by every use case that writes — not an aspect, which cannot know what happened
 * to which target.
 *
 * <p>Fail-open: an audit problem must never fail or block the business operation it records. The
 * problem itself is not silent either; every adapter counts it (observability.md §4, {@code
 * unihub_audit_publish_failures_total}).
 */
public interface AuditTrail {

    /**
     * Never throws. Takes effect after the surrounding transaction commits — an operation that
     * rolled back is never audited — or immediately when there is no transaction.
     */
    void record(AuditEntry entry);
}
