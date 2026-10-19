package com.unihub.shared.infrastructure.out.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.shared.application.port.out.AuditEntry;
import com.unihub.shared.application.port.out.AuditTrail;
import com.unihub.shared.security.Actor;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import java.util.LinkedHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The Phase 2 {@link AuditTrail}: one structured JSON line per audited operation on the dedicated
 * {@code unihub.audit} logger, which {@code logback-spring.xml} sends through a non-blocking
 * asynchronous appender. It does not publish to RabbitMQ: a queue with no consumer grows without
 * bound, and a broker memory alarm would block the outbox relay too. {@code RabbitAuditTrail}
 * replaces it in Phase 6 together with the consumer that persists {@code audit_events}; no use case
 * changes (backend-coding-standards.md §12, Phase 2 checklist D6).
 *
 * <p>Fail-open and not silent: nothing here throws, and every entry that could not be written
 * increments {@code unihub.audit.publish.failures}.
 */
@Component
class LoggingAuditTrail implements AuditTrail {

    static final String AUDIT_LOGGER = "unihub.audit";
    static final String FAILURE_COUNTER = "unihub.audit.publish.failures";

    private static final Logger AUDIT = LoggerFactory.getLogger(AUDIT_LOGGER);
    private static final Logger LOG = LoggerFactory.getLogger(LoggingAuditTrail.class);

    private final ObjectMapper json;
    private final Counter failures;

    LoggingAuditTrail(ObjectMapper json) {
        this.json = json;
        // The global registry is where Micrometer registries attach once the observability stack
        // (Phase 6) exists; until then the counter is a no-op.
        this.failures = Metrics.counter(FAILURE_COUNTER);
    }

    @Override
    public void record(AuditEntry entry) {
        try {
            // Formatted now, not later: what is logged is the state at the time of the call.
            var line = format(entry);
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                write(line);
                            }
                        });
            } else {
                write(line);
            }
        } catch (RuntimeException | JsonProcessingException e) {
            failed(entry, e);
        }
    }

    private String format(AuditEntry entry) throws JsonProcessingException {
        var line = new LinkedHashMap<String, Object>();
        var metadata = new LinkedHashMap<String, Object>(entry.metadata());
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
        return json.writeValueAsString(line);
    }

    private void write(String line) {
        try {
            AUDIT.info(line);
        } catch (RuntimeException e) {
            failed(null, e);
        }
    }

    private void failed(AuditEntry entry, Exception cause) {
        failures.increment();
        try {
            LOG.warn(
                    "Audit entry not recorded{}: {}",
                    entry == null ? "" : " (" + entry.action() + " " + entry.targetId() + ")",
                    cause.toString());
        } catch (RuntimeException ignored) {
            // The counter above is the signal; a broken logger must not turn into a business
            // failure.
        }
    }
}
