package com.unihub.shared.web;

import io.micrometer.tracing.Tracer;
import org.slf4j.MDC;

/**
 * The correlation id of the work in progress on this thread (observability.md §1, Phase 6 checklist
 * D65, D66). It is the trace id of the current span, never a second identifier. A span exists for
 * every request and every job cycle whether or not the sampler keeps it: sampling decides what is
 * exported, not whether the trace id exists, so the id is always there where tracing is on.
 */
public final class CorrelationIds {

    /** The response header; a header of this name on a request is ignored (D65). */
    public static final String HEADER = "X-Correlation-Id";

    /** The MDC key the log pattern, the outbox and the message header all read. */
    public static final String MDC_KEY = "correlationId";

    private CorrelationIds() {}

    /** The trace id of the current span, or {@code null} when there is none (tracing is off). */
    public static String currentTraceId(Tracer tracer) {
        if (tracer == null) {
            return null;
        }
        var span = tracer.currentSpan();
        if (span == null) {
            return null;
        }
        var traceId = span.context().traceId();
        return traceId == null || traceId.isBlank() ? null : traceId;
    }

    /** What the filter put in the MDC for this thread, or {@code null}. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }
}
