package com.unihub.shared.infrastructure.in.scheduling;

import com.unihub.shared.web.CorrelationIds;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Runs one cycle of a scheduled job as an observation of its own, so that the work has a trace and
 * therefore a correlation id although no request started it (observability.md §1, Phase 6 checklist
 * D66, 3.2). The id is in the MDC for the whole cycle: its log lines carry it, and an outbox row it
 * writes stores it ({@code event_publications.correlation_id}), so the consumer that handles the
 * message later logs under the same id.
 *
 * <p>Where tracing is off (a test that has not asked for it) the cycle simply runs; there is no id
 * to set and none is made up.
 */
@Component
public final class JobCycle {

    private final ObservationRegistry observations;
    private final Tracer tracer;

    @Autowired
    JobCycle(ObservationRegistry observations, ObjectProvider<Tracer> tracer) {
        this(observations, tracer.getIfAvailable());
    }

    private JobCycle(ObservationRegistry observations, Tracer tracer) {
        this.observations = observations;
        this.tracer = tracer;
    }

    /** A cycle runner that observes nothing, for a unit test of a scheduler's own logic. */
    public static JobCycle untraced() {
        return new JobCycle(ObservationRegistry.NOOP, (Tracer) null);
    }

    /** Runs {@code cycle}; {@code job} is the low-cardinality name of the job. */
    public void run(String job, Runnable cycle) {
        Observation.createNotStarted("unihub.job.cycle", observations)
                .lowCardinalityKeyValue("job", job)
                .observe(
                        () -> {
                            var previous = MDC.get(CorrelationIds.MDC_KEY);
                            var correlationId = CorrelationIds.currentTraceId(tracer);
                            if (correlationId != null) {
                                MDC.put(CorrelationIds.MDC_KEY, correlationId);
                            }
                            try {
                                cycle.run();
                            } finally {
                                if (previous == null) {
                                    MDC.remove(CorrelationIds.MDC_KEY);
                                } else {
                                    MDC.put(CorrelationIds.MDC_KEY, previous);
                                }
                            }
                        });
    }
}
