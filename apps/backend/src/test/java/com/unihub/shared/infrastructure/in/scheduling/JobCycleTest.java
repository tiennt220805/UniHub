package com.unihub.shared.infrastructure.in.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * A scheduled cycle has a correlation id for as long as it runs, and leaves the MDC as it found it
 * (Phase 6 checklist D66, 3.2). The span itself comes from the observation; here a tracer that
 * already has one stands in, since what is under test is what the cycle does with its trace id.
 */
class JobCycleTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    @AfterEach
    void clean() {
        MDC.clear();
    }

    private static JobCycle cycleWithTrace(String traceId) {
        var tracer = mock(Tracer.class);
        var span = mock(Span.class);
        var context = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(context);
        when(context.traceId()).thenReturn(traceId);
        var beans = new StaticListableBeanFactory();
        beans.addBean("tracer", tracer);
        return new JobCycle(ObservationRegistry.NOOP, beans.getBeanProvider(Tracer.class));
    }

    @Test
    @DisplayName("the trace id is the correlation id while the cycle runs, and is gone afterwards")
    void idDuringTheCycleOnly() {
        var seen = new AtomicReference<String>();

        cycleWithTrace(TRACE_ID).run("outbox-relay", () -> seen.set(MDC.get("correlationId")));

        assertThat(seen.get()).isEqualTo(TRACE_ID);
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    @DisplayName("the MDC is cleared when the cycle throws, and the exception goes on")
    void clearedOnFailure() {
        assertThatThrownBy(
                        () ->
                                cycleWithTrace(TRACE_ID)
                                        .run(
                                                "outbox-relay",
                                                () -> {
                                                    throw new IllegalStateException("db down");
                                                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    @DisplayName("what the MDC held before is put back, not removed")
    void previousValueIsRestored() {
        MDC.put("correlationId", "earlier");

        cycleWithTrace(TRACE_ID).run("outbox-relay", () -> {});

        assertThat(MDC.get("correlationId")).isEqualTo("earlier");
    }

    @Test
    @DisplayName("without tracing the cycle runs and no id is made up")
    void untraced() {
        var seen = new AtomicReference<String>("unset");

        JobCycle.untraced().run("outbox-relay", () -> seen.set(MDC.get("correlationId")));

        assertThat(seen.get()).isNull();
    }
}
