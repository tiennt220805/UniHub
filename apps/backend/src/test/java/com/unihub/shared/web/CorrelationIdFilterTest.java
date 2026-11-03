package com.unihub.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.ServletException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * What {@link CorrelationIdFilter} promises (Phase 6 checklist D65, 3.1): the trace id of the
 * current span is in the MDC while the request runs and on the response, whatever the client sent,
 * and the MDC is clean afterwards even when the chain throws.
 */
class CorrelationIdFilterTest {

    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";

    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/x");
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    private static Tracer tracerWithTrace(String traceId) {
        var tracer = mock(Tracer.class);
        var span = mock(Span.class);
        var context = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(context);
        when(context.traceId()).thenReturn(traceId);
        return tracer;
    }

    @AfterEach
    void clean() {
        MDC.clear();
    }

    @Test
    @DisplayName("the trace id is in the MDC during the request and on the response")
    void traceIdIsTheCorrelationId() throws Exception {
        var seen = new AtomicReference<String>();

        new CorrelationIdFilter(tracerWithTrace(TRACE_ID))
                .doFilter(request, response, (req, res) -> seen.set(MDC.get("correlationId")));

        assertThat(seen.get()).isEqualTo(TRACE_ID);
        assertThat(response.getHeader("X-Correlation-Id")).isEqualTo(TRACE_ID);
        assertThat(MDC.get("correlationId")).as("removed afterwards").isNull();
    }

    @Test
    @DisplayName("a value the client sent is ignored, never echoed and never used")
    void aClientSuppliedValueIsIgnored() throws Exception {
        request.addHeader("X-Correlation-Id", "abc");
        var seen = new AtomicReference<String>();

        new CorrelationIdFilter(tracerWithTrace(TRACE_ID))
                .doFilter(request, response, (req, res) -> seen.set(MDC.get("correlationId")));

        assertThat(seen.get()).isEqualTo(TRACE_ID);
        assertThat(response.getHeader("X-Correlation-Id")).isEqualTo(TRACE_ID);
    }

    @Test
    @DisplayName(
            "the MDC is cleared when the chain throws, so a pooled thread carries nothing over")
    void clearedOnFailure() {
        var filter = new CorrelationIdFilter(tracerWithTrace(TRACE_ID));

        assertThatThrownBy(
                        () ->
                                filter.doFilter(
                                        request,
                                        response,
                                        (req, res) -> {
                                            throw new ServletException("boom");
                                        }))
                .isInstanceOf(ServletException.class);

        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    @DisplayName("with no tracer, or no current span, nothing is set and the request still runs")
    void withoutTracing() throws Exception {
        var ran = new AtomicReference<Boolean>(false);
        var noSpan = mock(Tracer.class);

        new CorrelationIdFilter(null).doFilter(request, response, (req, res) -> ran.set(true));
        new CorrelationIdFilter(noSpan).doFilter(request, response, (req, res) -> {});

        assertThat(ran.get()).isTrue();
        assertThat(response.getHeader("X-Correlation-Id")).isNull();
    }
}
