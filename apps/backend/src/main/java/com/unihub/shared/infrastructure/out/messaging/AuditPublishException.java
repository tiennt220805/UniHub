package com.unihub.shared.infrastructure.out.messaging;

/**
 * An audit message that did not reach {@code unihub.audit.queue}. {@link Reason} is the tag of
 * {@code unihub_audit_publish_failures_total} (observability.md §4).
 */
public final class AuditPublishException extends RuntimeException {

    /** The tag values of the failure counter; every one is registered at 0 at startup. */
    public enum Reason {
        /**
         * Never handed to the broker: the executor's queue was full, or it was left at shutdown.
         */
        DROPPED,
        /** The broker refused it, including because {@code unihub.audit.queue} is full. */
        NACK,
        /** The broker did not answer within the timeout. */
        TIMEOUT,
        /** Anything else: no connection, an unroutable message, an unexpected exception. */
        ERROR;

        public String tag() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private final Reason reason;

    public AuditPublishException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
