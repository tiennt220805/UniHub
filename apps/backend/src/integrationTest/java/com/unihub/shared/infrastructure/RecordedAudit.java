package com.unihub.shared.infrastructure;

import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.shared.application.port.out.AuditEntry;
import com.unihub.shared.application.port.out.AuditTrail;
import com.unihub.shared.infrastructure.out.messaging.AuditPublisher;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The audit messages an application context handed to its publisher, for the tests that ask what a
 * use case recorded (actor, action, target, metadata), not whether it reached the table: the real
 * {@code RabbitAuditTrail} runs — after commit, off the calling thread, counted on failure — and
 * only the last step, the broker, is replaced. That an entry reaches {@code audit_events} through
 * the real broker and consumer is {@code AuditTrailIT}'s and {@code AuditRedeliveryIT}'s job.
 *
 * <p>The trail publishes from its own thread, so "nothing was audited" cannot be read off the list
 * the moment the use case returns. {@link #settle} sends a marker entry through the same trail and
 * waits for it: the executor is one thread and first in, first out, so everything recorded before
 * the marker has been published (or failed) by the time the marker is seen.
 *
 * <p>Use it with {@code @Import(RecordedAudit.Config.class)}.
 */
public final class RecordedAudit implements AuditPublisher {

    private static final String MARKER = "TEST_SETTLE_MARKER";
    private static final ObjectMapper READER = new ObjectMapper();

    private final List<JsonNode> published = new CopyOnWriteArrayList<>();
    private volatile RuntimeException failure;

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {
        @Bean
        @Primary
        RecordedAudit recordedAudit() {
            return new RecordedAudit();
        }
    }

    @Override
    public void publish(String messageId, byte[] body, String correlationId) {
        var failure = this.failure;
        if (failure != null) {
            throw failure;
        }
        try {
            published.add(READER.readTree(body));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** From now on every publish fails with {@code failure} (null: publishes work again). */
    public void failWith(RuntimeException failure) {
        this.failure = failure;
    }

    public void clear() {
        published.clear();
    }

    /** Waits until everything recorded so far has been published, then returns it. */
    public List<JsonNode> settle(AuditTrail trail) {
        var marker = UUID.randomUUID();
        trail.record(
                AuditEntry.of(
                        com.unihub.shared.security.Actor.system("test-settle"),
                        MARKER,
                        "TEST",
                        marker,
                        Instant.now()));
        await().atMost(Duration.ofSeconds(10))
                .until(
                        () ->
                                published.stream()
                                        .anyMatch(
                                                m ->
                                                        marker.toString()
                                                                .equals(
                                                                        m.path("targetId")
                                                                                .asText())));
        var entries =
                published.stream().filter(m -> !MARKER.equals(m.path("action").asText())).toList();
        published.removeIf(m -> MARKER.equals(m.path("action").asText()));
        return entries;
    }
}
