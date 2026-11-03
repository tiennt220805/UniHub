package com.unihub.shared.web;

import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts the trace id of the request into the MDC and onto the response (observability.md §1, Phase 6
 * checklist D65, 3.1). It runs after Boot's observation filter, which has already started the
 * request's span (continuing an upstream {@code traceparent} or starting a new trace), and before
 * the Spring Security chain, so a {@code 401} or {@code 403} written by a security filter carries
 * the header and the body field too.
 *
 * <p>The id is always the trace id. An {@code X-Correlation-Id} the client sent is not read:
 * letting a client choose it would let two requests share an id on purpose and muddy an
 * investigation. The MDC entry is removed in {@code finally}, because servlet threads are pooled
 * and a stale id would label the next request's log lines.
 */
public final class CorrelationIdFilter extends OncePerRequestFilter {

    private final Tracer tracer;

    public CorrelationIdFilter(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var correlationId = CorrelationIds.currentTraceId(tracer);
        if (correlationId == null) {
            chain.doFilter(request, response);
            return;
        }
        MDC.put(CorrelationIds.MDC_KEY, correlationId);
        response.setHeader(CorrelationIds.HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(CorrelationIds.MDC_KEY);
        }
    }
}
