package com.unihub.shared.infrastructure.out.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.unihub.shared.application.port.out.AuditActions;
import com.unihub.shared.application.port.out.AuditEntry;
import com.unihub.shared.application.port.out.AuditTargets;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.security.Actor;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class LoggingAuditTrailTest {

    private static final Instant AT = Instant.parse("2026-10-19T08:00:00Z");
    private static final UserId USER = UserId.newId();
    private static final UUID TARGET = UUID.randomUUID();

    private final ObjectMapper json =
            new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ListAppender<ILoggingEvent> lines = new ListAppender<>();
    private final Logger auditLogger = (Logger) LoggerFactory.getLogger("unihub.audit");

    @BeforeEach
    void attach() {
        Metrics.addRegistry(meters);
        lines.start();
        auditLogger.addAppender(lines);
        auditLogger.setLevel(Level.INFO);
    }

    @AfterEach
    void detach() {
        auditLogger.detachAppender(lines);
        Metrics.removeRegistry(meters);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private LoggingAuditTrail trail() {
        return new LoggingAuditTrail(json);
    }

    private static AuditEntry entry(Actor actor) {
        return AuditEntry.of(
                actor, AuditActions.WORKSHOP_CANCELLED, AuditTargets.WORKSHOP, TARGET, AT);
    }

    private double failures() {
        var counter = meters.find("unihub.audit.publish.failures").counter();
        return counter == null ? 0 : counter.count();
    }

    private JsonNode onlyLine() throws Exception {
        assertThat(lines.list).hasSize(1);
        return json.readTree(lines.list.getFirst().getFormattedMessage());
    }

    @Test
    @DisplayName("with no transaction the entry is logged immediately as one JSON line")
    void noTransaction_logsImmediately() throws Exception {
        trail().record(entry(new Actor.UserActor(USER, Set.of("WORKSHOP_EDIT"))));

        var line = onlyLine();
        assertThat(line.get("actorType").asText()).isEqualTo("USER");
        assertThat(line.get("actorUserId").asText()).isEqualTo(USER.value().toString());
        assertThat(line.get("action").asText()).isEqualTo("WORKSHOP_CANCELLED");
        assertThat(line.get("targetType").asText()).isEqualTo("WORKSHOP");
        assertThat(line.get("targetId").asText()).isEqualTo(TARGET.toString());
        assertThat(line.get("occurredAt").asText()).isEqualTo("2026-10-19T08:00:00Z");
        assertThat(failures()).isZero();
    }

    @Test
    @DisplayName("a SYSTEM actor has no user id and names its process in the metadata")
    void systemActor_isMappedLikeAuditEvents() throws Exception {
        trail().record(entry(Actor.system("seat-hold-expiry")));

        var line = onlyLine();
        assertThat(line.get("actorType").asText()).isEqualTo("SYSTEM");
        assertThat(line.has("actorUserId")).isFalse();
        assertThat(line.get("metadata").get("process").asText()).isEqualTo("seat-hold-expiry");
    }

    @Test
    @DisplayName("entry metadata is carried into the line")
    void metadata_isLogged() throws Exception {
        trail().record(
                        entry(new Actor.UserActor(USER, Set.of()))
                                .withMetadata(Map.of("newStartTime", "2026-12-02T09:00:00Z")));

        assertThat(onlyLine().get("metadata").get("newStartTime").asText())
                .isEqualTo("2026-12-02T09:00:00Z");
    }

    @Test
    @DisplayName("inside a transaction nothing is logged until it commits")
    void inTransaction_logsOnlyAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();

        trail().record(entry(new Actor.UserActor(USER, Set.of())));

        assertThat(lines.list).as("before commit").isEmpty();
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
        assertThat(lines.list).as("after commit").hasSize(1);
    }

    @Test
    @DisplayName("a rolled-back transaction is never audited")
    void rolledBack_isNeverLogged() {
        TransactionSynchronizationManager.initSynchronization();

        trail().record(entry(new Actor.UserActor(USER, Set.of())));
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        assertThat(lines.list).isEmpty();
    }

    @Test
    @DisplayName("an entry that cannot be serialized never throws: it is counted instead")
    void unserializable_neverThrows_isCounted() {
        var poison =
                Map.<String, Object>of(
                        "bad",
                        new Object() {
                            @SuppressWarnings("unused")
                            public String getBoom() {
                                throw new IllegalStateException("cannot serialize");
                            }
                        });

        assertThatCode(
                        () ->
                                trail().record(
                                                entry(new Actor.UserActor(USER, Set.of()))
                                                        .withMetadata(poison)))
                .doesNotThrowAnyException();

        assertThat(lines.list).isEmpty();
        assertThat(failures()).isEqualTo(1);
    }

    @Test
    @DisplayName("a failing log appender after commit never makes the commit fail")
    void appenderFailure_afterCommit_neverThrows() {
        auditLogger.addAppender(
                new ListAppender<>() {
                    {
                        start();
                    }

                    @Override
                    protected void append(ILoggingEvent event) {
                        throw new IllegalStateException("disk full");
                    }
                });
        TransactionSynchronizationManager.initSynchronization();
        trail().record(entry(new Actor.UserActor(USER, Set.of())));

        assertThatCode(
                        () ->
                                TransactionSynchronizationManager.getSynchronizations()
                                        .forEach(TransactionSynchronization::afterCommit))
                .doesNotThrowAnyException();
    }
}
