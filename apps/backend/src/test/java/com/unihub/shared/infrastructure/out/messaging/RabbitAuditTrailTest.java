package com.unihub.shared.infrastructure.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.unihub.shared.application.port.out.AuditActions;
import com.unihub.shared.application.port.out.AuditEntry;
import com.unihub.shared.application.port.out.AuditTargets;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.infrastructure.out.messaging.AuditPublishException.Reason;
import com.unihub.shared.security.Actor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.CurrentTraceContext;
import io.micrometer.tracing.TraceContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Every branch of the audit publisher's contract (Phase 6 checklist D68, 2.2; INV-14): an entry is
 * published after commit and never for a rollback; the calling thread never waits for the broker;
 * and every entry that does not reach the queue is counted exactly once, under the reason that says
 * why.
 */
class RabbitAuditTrailTest {

    private static final Instant AT = Instant.parse("2026-11-03T08:00:00Z");
    private static final UserId USER = UserId.newId();
    private static final UUID TARGET = UUID.randomUUID();
    private static final Duration SECOND = Duration.ofSeconds(1);

    private final ObjectMapper json =
            new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final FakePublisher publisher = new FakePublisher();
    private final List<RabbitAuditTrail> created = new CopyOnWriteArrayList<>();

    /** Records what it is asked to send; can block, or fail with a chosen exception. */
    private static final class FakePublisher implements AuditPublisher {
        record Sent(String messageId, JsonNode body, String correlationId, String threadMdc) {}

        final List<Sent> sent = new CopyOnWriteArrayList<>();
        volatile CountDownLatch gate;
        volatile RuntimeException failure;
        volatile Thread caller;
        private final ObjectMapper reader = new ObjectMapper();

        @Override
        public void publish(String messageId, byte[] body, String correlationId) {
            caller = Thread.currentThread();
            var gate = this.gate;
            if (gate != null) {
                try {
                    gate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted", e);
                }
            }
            var failure = this.failure;
            if (failure != null) {
                throw failure;
            }
            try {
                sent.add(
                        new Sent(
                                messageId,
                                reader.readTree(body),
                                correlationId,
                                MDC.get("correlationId")));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static ExecutorService executor(int capacity) {
        return new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity),
                task -> {
                    var thread = new Thread(task, "audit-publisher-test");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    private RabbitAuditTrail trail() {
        return trail(10, json, SECOND);
    }

    private RabbitAuditTrail trail(int capacity, ObjectMapper mapper, Duration drain) {
        var trail = new RabbitAuditTrail(publisher, mapper, meters, executor(capacity), drain);
        created.add(trail);
        return trail;
    }

    @AfterEach
    void cleanUp() {
        var gate = publisher.gate;
        if (gate != null) {
            gate.countDown();
        }
        created.forEach(RabbitAuditTrail::destroy);
        MDC.clear();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static AuditEntry userEntry() {
        return AuditEntry.of(
                new Actor.UserActor(USER, Set.of()),
                AuditActions.WORKSHOP_CANCELLED,
                AuditTargets.WORKSHOP,
                TARGET,
                AT);
    }

    private static AuditEntry systemEntry() {
        return AuditEntry.of(
                        Actor.system("seat-hold-expiry"),
                        AuditActions.WORKSHOP_CANCELLED,
                        AuditTargets.WORKSHOP,
                        TARGET,
                        AT)
                .withMetadata(Map.of("reason", "expired"));
    }

    private double failures(Reason reason) {
        var counter =
                meters.find(RabbitAuditTrail.FAILURE_COUNTER).tag("reason", reason.tag()).counter();
        assertThat(counter).as("the " + reason.tag() + " series is registered").isNotNull();
        return counter.count();
    }

    private double allFailures() {
        double total = 0;
        for (var reason : Reason.values()) {
            total += failures(reason);
        }
        return total;
    }

    private void awaitSent(int count) {
        await().atMost(Duration.ofSeconds(5)).until(() -> publisher.sent.size() == count);
    }

    @Test
    @DisplayName(
            "every reason is registered at 0 before anything fails (a series born above 0 is"
                    + " invisible to increase())")
    void everyReasonIsRegisteredAtZero() {
        trail();

        for (var reason : Reason.values()) {
            assertThat(failures(reason)).as(reason.tag()).isZero();
        }
        assertThat(Reason.values())
                .extracting(Reason::tag)
                .containsExactly("dropped", "nack", "timeout", "error");
    }

    @Test
    @DisplayName(
            "the trace context current when record() is called is current again on the executor"
                    + " thread while it publishes, and the scope is closed after")
    void requestTrace_isRestoredOnTheExecutorThread() {
        var traces = org.mockito.Mockito.mock(CurrentTraceContext.class);
        var context = org.mockito.Mockito.mock(TraceContext.class);
        var scope = org.mockito.Mockito.mock(CurrentTraceContext.Scope.class);
        var enteredOn = new java.util.concurrent.atomic.AtomicReference<Thread>();
        org.mockito.Mockito.when(traces.context()).thenReturn(context);
        org.mockito.Mockito.when(traces.maybeScope(context))
                .thenAnswer(
                        call -> {
                            enteredOn.set(Thread.currentThread());
                            return scope;
                        });
        var trail = new RabbitAuditTrail(publisher, json, meters, executor(10), SECOND, traces);
        created.add(trail);

        trail.record(userEntry());

        awaitSent(1);
        assertThat(enteredOn.get()).isNotNull().isNotSameAs(Thread.currentThread());
        org.mockito.Mockito.verify(scope, org.mockito.Mockito.timeout(2000)).close();
    }

    @Test
    @DisplayName("with no transaction the entry is published at once, on the executor thread")
    void withoutATransaction_publishesAtOnce() {
        trail().record(userEntry());

        awaitSent(1);
        assertThat(publisher.caller).isNotSameAs(Thread.currentThread());
        assertThat(allFailures()).isZero();
    }

    @Test
    @DisplayName(
            "inside a transaction nothing is sent before the commit, and a rollback sends"
                    + " nothing at all")
    void inATransaction_publishesAfterCommitOnly() throws Exception {
        TransactionSynchronizationManager.initSynchronization();
        trail().record(userEntry());

        Thread.sleep(150);
        assertThat(publisher.sent).as("not before the commit").isEmpty();

        // rollback: the synchronizations get afterCompletion(STATUS_ROLLED_BACK), never afterCommit
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        TransactionSynchronizationManager.clearSynchronization();
        Thread.sleep(150);
        assertThat(publisher.sent).as("a rolled-back operation is never audited").isEmpty();

        TransactionSynchronizationManager.initSynchronization();
        trail().record(userEntry());
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
        TransactionSynchronizationManager.clearSynchronization();

        awaitSent(1);
        assertThat(allFailures()).isZero();
    }

    @Test
    @DisplayName("a user entry carries the user, a system entry the process in its metadata")
    void actorsAreMappedTheWayAuditEventsStoresThem() {
        var trail = trail();

        trail.record(userEntry());
        trail.record(systemEntry());

        awaitSent(2);
        var user = publisher.sent.get(0).body();
        assertThat(user.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(user.get("actorType").asText()).isEqualTo("USER");
        assertThat(user.get("actorUserId").asText()).isEqualTo(USER.value().toString());
        assertThat(user.get("action").asText()).isEqualTo("WORKSHOP_CANCELLED");
        assertThat(user.get("targetType").asText()).isEqualTo("WORKSHOP");
        assertThat(user.get("targetId").asText()).isEqualTo(TARGET.toString());
        assertThat(user.get("occurredAt").asText()).isEqualTo("2026-11-03T08:00:00Z");
        var system = publisher.sent.get(1).body();
        assertThat(system.get("actorType").asText()).isEqualTo("SYSTEM");
        assertThat(system.has("actorUserId")).isFalse();
        assertThat(system.get("metadata").get("process").asText()).isEqualTo("seat-hold-expiry");
        assertThat(system.get("metadata").get("reason").asText()).isEqualTo("expired");
    }

    @Test
    @DisplayName("each entry gets a message-id of its own: the consumer deduplicates on it")
    void eachEntryHasItsOwnMessageId() {
        var trail = trail();

        trail.record(userEntry());
        trail.record(userEntry());

        awaitSent(2);
        var ids = publisher.sent.stream().map(FakePublisher.Sent::messageId).toList();
        assertThat(ids).doesNotHaveDuplicates().allSatisfy(id -> UUID.fromString(id));
    }

    @Test
    @DisplayName(
            "the correlation id of the calling thread is the one sent, and the executor thread"
                    + " is left clean for the next entry")
    void theCorrelationIdIsSnapshottedOnTheCallingThread() {
        var trail = trail();
        MDC.put("correlationId", "0af7651916cd43dd8448eb211c80319c");
        trail.record(userEntry());
        MDC.remove("correlationId");
        trail.record(userEntry());

        awaitSent(2);
        assertThat(publisher.sent.get(0).correlationId())
                .isEqualTo("0af7651916cd43dd8448eb211c80319c");
        assertThat(publisher.sent.get(0).threadMdc())
                .as("the executor thread logs under the request's id")
                .isEqualTo("0af7651916cd43dd8448eb211c80319c");
        assertThat(publisher.sent.get(1).correlationId()).isNull();
        assertThat(publisher.sent.get(1).threadMdc())
                .as("not carried over to the next entry")
                .isNull();
    }

    @Test
    @DisplayName("the calling thread never waits for a publisher that is stuck")
    void aStuckPublisherDoesNotHoldTheCaller() {
        publisher.gate = new CountDownLatch(1);
        var trail = trail();
        var start = System.nanoTime();

        for (int i = 0; i < 5; i++) {
            trail.record(userEntry());
        }

        assertThat(Duration.ofNanos(System.nanoTime() - start))
                .as("record() returns without waiting for the broker")
                .isLessThan(Duration.ofMillis(500));
    }

    @Test
    @DisplayName("a full executor drops the entry and counts it as dropped, once")
    void aFullExecutorDropsAndCounts() {
        publisher.gate = new CountDownLatch(1);
        var trail = trail(1, json, SECOND);

        trail.record(userEntry()); // taken by the thread, blocked in the publisher
        await().atMost(SECOND.multipliedBy(5)).until(() -> publisher.caller != null);
        trail.record(userEntry()); // waits in the queue
        trail.record(userEntry()); // refused

        assertThat(failures(Reason.DROPPED)).isEqualTo(1);
        publisher.gate.countDown();
        awaitSent(2);
        assertThat(allFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("a nack, a timeout and an error are counted under their own reason")
    void eachFailureIsCountedUnderItsReason() {
        var trail = trail();

        publisher.failure = new AuditPublishException(Reason.NACK, "refused", null);
        trail.record(userEntry());
        await().atMost(Duration.ofSeconds(5)).until(() -> failures(Reason.NACK) == 1);

        publisher.failure = new AuditPublishException(Reason.TIMEOUT, "no confirm", null);
        trail.record(userEntry());
        await().atMost(Duration.ofSeconds(5)).until(() -> failures(Reason.TIMEOUT) == 1);

        publisher.failure = new AuditPublishException(Reason.ERROR, "no connection", null);
        trail.record(userEntry());
        await().atMost(Duration.ofSeconds(5)).until(() -> failures(Reason.ERROR) == 1);

        publisher.failure = new IllegalStateException("anything else");
        trail.record(userEntry());
        await().atMost(Duration.ofSeconds(5)).until(() -> failures(Reason.ERROR) == 2);

        assertThat(failures(Reason.NACK)).isEqualTo(1);
        assertThat(failures(Reason.TIMEOUT)).isEqualTo(1);
        assertThat(failures(Reason.DROPPED)).isZero();
        assertThat(publisher.sent).isEmpty();
    }

    @Test
    @DisplayName(
            "an entry that cannot be formatted is counted as an error and record() still"
                    + " returns normally")
    void anEntryThatCannotBeFormatted_isCounted() {
        var broken =
                new ObjectMapper() {
                    @Override
                    public String writeValueAsString(Object value) throws JsonProcessingException {
                        throw new JsonProcessingException("cannot") {};
                    }
                };
        var trail = trail(10, broken, SECOND);

        assertThatCode(() -> trail.record(userEntry())).doesNotThrowAnyException();

        assertThat(failures(Reason.ERROR)).isEqualTo(1);
        assertThat(publisher.sent).isEmpty();
    }

    @Test
    @DisplayName("record() never throws, even for no entry")
    void recordNeverThrows() {
        var trail = trail();

        assertThatCode(() -> trail.record(null)).doesNotThrowAnyException();

        assertThat(failures(Reason.ERROR)).isEqualTo(1);
    }

    @Test
    @DisplayName("an entry recorded after shutdown is counted as dropped")
    void afterShutdown_isDropped() {
        var trail = trail();
        trail.destroy();

        trail.record(userEntry());

        assertThat(failures(Reason.DROPPED)).isEqualTo(1);
    }

    @Test
    @DisplayName("shutdown lets queued entries go out and counts nothing when they all do")
    void shutdownDrains() {
        var trail = trail();
        for (int i = 0; i < 5; i++) {
            trail.record(userEntry());
        }

        trail.destroy();

        assertThat(publisher.sent).hasSize(5);
        assertThat(allFailures()).isZero();
    }

    @Test
    @DisplayName(
            "shutdown counts what it could not send: the entries still queued as dropped, the"
                    + " one in flight once, as its own failure")
    void shutdownCountsWhatIsLeft() {
        publisher.gate = new CountDownLatch(1);
        var trail = trail(10, json, Duration.ofMillis(200));
        for (int i = 0; i < 4; i++) {
            trail.record(userEntry()); // one in flight, three queued
        }
        await().atMost(SECOND.multipliedBy(5)).until(() -> publisher.caller != null);

        trail.destroy();

        assertThat(failures(Reason.DROPPED)).as("the three that never started").isEqualTo(3);
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () ->
                                assertThat(failures(Reason.ERROR))
                                        .as("the interrupted one, counted by itself")
                                        .isEqualTo(1));
        assertThat(allFailures()).as("every entry exactly once").isEqualTo(4);
    }
}
