package com.unihub.shared.infrastructure.out.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.shared.application.port.out.AuditEntry;
import com.unihub.shared.application.port.out.AuditTrail;
import com.unihub.shared.infrastructure.out.messaging.AuditPublishException.Reason;
import com.unihub.shared.security.Actor;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.CurrentTraceContext;
import io.micrometer.tracing.TraceContext;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The {@link AuditTrail} of Phase 6 (backend-coding-standards.md §12, messaging.md §3, checklist
 * D68): publishes each entry to the audit routing key (messaging.md §3), directly and not through
 * the outbox, and <b>never on the calling thread</b>. {@code record} formats the entry, snapshots
 * the correlation id and the observation context, and (after the surrounding transaction commits,
 * or at once without one) hands it to a bounded single-thread executor. The request never touches
 * the broker's socket, so a broker that is stopped, paused or under a memory alarm cannot slow it.
 *
 * <p>Fail-open and not silent (INV-14, architecture.md §5.5): nothing here throws, and every entry
 * that does not reach the queue increments {@code unihub_audit_publish_failures_total{reason}}
 * exactly once. Every {@code reason} series is registered at 0 at construction: Prometheus {@code
 * increase()} ignores a series that is born above 0, so one created by the first failure could hide
 * the first outage from its alert.
 */
public final class RabbitAuditTrail implements AuditTrail, DisposableBean {

    public static final String FAILURE_COUNTER = "unihub.audit.publish.failures";

    private static final int SCHEMA_VERSION = 1;
    private static final Logger LOG = LoggerFactory.getLogger(RabbitAuditTrail.class);
    private static final ContextSnapshotFactory SNAPSHOTS =
            ContextSnapshotFactory.builder().build();

    /** What the executor thread needs, taken on the calling thread. */
    private record Job(
            String messageId,
            byte[] body,
            String correlationId,
            ContextSnapshot context,
            TraceContext trace,
            String description) {}

    private final AuditPublisher publisher;
    private final ObjectMapper json;
    private final ExecutorService executor;
    private final Duration drainTimeout;
    private final CurrentTraceContext traces;
    private final Map<Reason, Counter> failures = new EnumMap<>(Reason.class);

    public RabbitAuditTrail(
            AuditPublisher publisher,
            ObjectMapper json,
            MeterRegistry meters,
            ExecutorService executor,
            Duration drainTimeout) {
        this(publisher, json, meters, executor, drainTimeout, null);
    }

    /**
     * {@code traces} is how the request's trace crosses to the executor thread: the observation
     * context alone is not enough, because the one current at {@code record()} is whatever the JDBC
     * layer left open (a no-op between statements), not the request's. Null where tracing is off.
     */
    public RabbitAuditTrail(
            AuditPublisher publisher,
            ObjectMapper json,
            MeterRegistry meters,
            ExecutorService executor,
            Duration drainTimeout,
            CurrentTraceContext traces) {
        this.traces = traces;
        this.publisher = publisher;
        this.json = json;
        this.executor = executor;
        this.drainTimeout = drainTimeout;
        for (var reason : Reason.values()) {
            failures.put(
                    reason,
                    Counter.builder(FAILURE_COUNTER)
                            .tag("reason", reason.tag())
                            .description("Audit entries that did not reach unihub.audit.queue")
                            .register(meters));
        }
    }

    @Override
    public void record(AuditEntry entry) {
        try {
            // Formatted now, not later: what is sent is the state at the time of the call.
            var job =
                    new Job(
                            UUID.randomUUID().toString(),
                            format(entry),
                            MDC.get(CorrelationIdPostProcessor.MDC_KEY),
                            SNAPSHOTS.captureAll(),
                            traces == null ? null : traces.context(),
                            describe(entry));
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                handOver(job);
                            }
                        });
            } else {
                handOver(job);
            }
        } catch (RuntimeException | JsonProcessingException e) {
            failed(Reason.ERROR, 1, describe(entry), e);
        }
    }

    /** For the log line only: the action and target, never the metadata. */
    private static String describe(AuditEntry entry) {
        return entry == null ? "no entry" : entry.action() + " " + entry.targetId();
    }

    /** Never throws: this runs inside the commit of somebody else's transaction. */
    private void handOver(Job job) {
        try {
            executor.execute(() -> publish(job));
        } catch (RejectedExecutionException e) {
            failed(Reason.DROPPED, 1, job.description(), e);
        } catch (RuntimeException e) {
            failed(Reason.ERROR, 1, job.description(), e);
        }
    }

    private void publish(Job job) {
        if (job.correlationId() != null) {
            MDC.put(CorrelationIdPostProcessor.MDC_KEY, job.correlationId());
        }
        try {
            try (var ignored = traces == null ? null : traces.maybeScope(job.trace())) {
                job.context()
                        .wrap(
                                () -> {
                                    try {
                                        publisher.publish(
                                                job.messageId(), job.body(), job.correlationId());
                                    } catch (AuditPublishException e) {
                                        failed(e.reason(), 1, job.description(), e);
                                    } catch (RuntimeException e) {
                                        failed(Reason.ERROR, 1, job.description(), e);
                                    }
                                })
                        .run();
            }
        } finally {
            MDC.remove(CorrelationIdPostProcessor.MDC_KEY);
        }
    }

    private byte[] format(AuditEntry entry) throws JsonProcessingException {
        var line = new LinkedHashMap<String, Object>();
        var metadata = new LinkedHashMap<String, Object>(entry.metadata());
        line.put("schemaVersion", SCHEMA_VERSION);
        switch (entry.actor()) {
            case Actor.UserActor user -> {
                line.put("actorType", "USER");
                line.put("actorUserId", user.userId().value());
            }
            case Actor.SystemActor system -> {
                line.put("actorType", "SYSTEM");
                metadata.put("process", system.process());
            }
        }
        line.put("action", entry.action());
        line.put("targetType", entry.targetType());
        line.put("targetId", entry.targetId());
        line.put("occurredAt", entry.occurredAt());
        line.put("metadata", metadata);
        return json.writeValueAsString(line).getBytes(StandardCharsets.UTF_8);
    }

    private void failed(Reason reason, int count, String what, Throwable cause) {
        failures.get(reason).increment(count);
        try {
            LOG.warn(
                    "Audit entry not recorded ({}) reason={}: {}",
                    what,
                    reason.tag(),
                    String.valueOf(cause));
        } catch (RuntimeException ignored) {
            // The counter above is the signal; a broken logger must not turn into a business
            // failure.
        }
    }

    /**
     * Lets queued entries go out for up to the drain timeout, then counts what is left as dropped.
     * The entry being sent when the time runs out counts itself, once, through its own failure
     * path: {@code shutdownNow} interrupts it, and the publisher closing its connection (it is
     * destroyed after this bean, which depends on it) unblocks a thread stuck on a blocked socket.
     * A JVM crash loses queued entries uncounted: a named limit (Phase 6 checklist D68).
     */
    @Override
    public void destroy() {
        executor.shutdown();
        try {
            if (executor.awaitTermination(drainTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        var left = executor.shutdownNow();
        if (!left.isEmpty()) {
            failed(
                    Reason.DROPPED,
                    left.size(),
                    left.size() + " entries left in the queue at shutdown",
                    null);
        }
    }
}
