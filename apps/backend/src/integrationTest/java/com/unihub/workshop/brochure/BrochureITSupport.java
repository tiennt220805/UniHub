package com.unihub.workshop.brochure;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aPublishedWorkshop;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.infrastructure.out.messaging.Topology;
import com.unihub.shared.security.TestJwts;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import com.unihub.workshop.infrastructure.config.ObjectStorageProperties;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * What the brochure ITs share (Phase 5 checklist 9.3–9.6): the real MinIO of {@link
 * IntegrationTestSupport} through the application's own S3 client, a WireMock stand-in for the AI
 * service, the real listener (auto-startup on, retry stages of 0.3 s), and messages put on the real
 * exchange the way the relay would. Subclasses are {@code @SpringBootTest}s that close their
 * context afterwards ({@code @DirtiesContext}): the AI stand-in is theirs alone.
 */
public abstract class BrochureITSupport extends IntegrationTestSupport {
    protected static final String SUMMARIZE = "/v1/summarize";
    protected static final byte[] A_PDF =
            "%PDF-1.7\n1 0 obj << /Type /Catalog >> endobj\n%%EOF"
                    .getBytes(StandardCharsets.US_ASCII);

    protected static WireMockServer ai;

    @BeforeAll
    static void startAi() {
        ai = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        ai.start();
    }

    @AfterAll
    static void stopAi() {
        ai.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> TestJwts.SECRET);
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "true");
        registry.add("unihub.messaging.retry-delays", () -> "PT0.3S,PT0.3S,PT0.3S");
        registry.add("unihub.ai-summarization.base-url", () -> "http://localhost:" + ai.port());
        registry.add("unihub.ai-summarization.api-key", () -> "test-key-not-a-secret");
        registry.add("unihub.ai-summarization.timeout", () -> "PT3S");
    }

    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected WorkshopRepository workshops;
    @Autowired protected TransactionTemplate tx;
    @Autowired protected RabbitTemplate rabbit;
    @Autowired protected AmqpAdmin amqpAdmin;
    @Autowired protected S3Client s3;
    @Autowired protected ObjectStorageProperties storage;

    private final List<UUID> workshopIds = new ArrayList<>();
    private final List<String> keys = new ArrayList<>();

    /**
     * A workshop with a confirmed brochure: the object is in the bucket and the key is on the row.
     */
    public record Brochure(UUID workshop, String key) {}

    @BeforeEach
    void resetAi() {
        ai.resetAll();
    }

    @AfterEach
    void cleanUpBrochures() {
        keys.forEach(this::deleteQuietly);
        keys.clear();
        for (var id : workshopIds) {
            jdbc.update("DELETE FROM event_publications WHERE aggregate_id = ?", id);
            jdbc.update("DELETE FROM workshops WHERE id = ?", id);
        }
        workshopIds.clear();
    }

    private void deleteQuietly(String key) {
        try {
            s3.deleteObject(r -> r.bucket(storage.bucket()).key(key));
        } catch (RuntimeException ignored) {
            // the bucket may be down in the outage tests; the container is theirs
        }
    }

    protected UUID aWorkshop() {
        var workshop = aPublishedWorkshop().build();
        tx.executeWithoutResult(s -> workshops.save(workshop));
        workshopIds.add(workshop.id().value());
        return workshop.id().value();
    }

    /** Puts {@code content} in the bucket under a fresh key of {@code workshop}. */
    protected String upload(UUID workshop, byte[] content) {
        var key = workshop + "/" + UUID.randomUUID() + ".pdf";
        s3.putObject(
                r -> r.bucket(storage.bucket()).key(key).contentType("application/pdf"),
                RequestBody.fromBytes(content));
        keys.add(key);
        return key;
    }

    protected Brochure aConfirmedBrochure(byte[] content) {
        var workshop = aWorkshop();
        var key = upload(workshop, content);
        jdbc.update("UPDATE workshops SET brochure_s3_key = ? WHERE id = ?", key, workshop);
        return new Brochure(workshop, key);
    }

    protected boolean objectExists(String key) {
        try {
            s3.headObject(r -> r.bucket(storage.bucket()).key(key));
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (software.amazon.awssdk.services.s3.model.S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    protected String summaryOf(UUID workshop) {
        return jdbc.queryForObject(
                "SELECT ai_summary FROM workshops WHERE id = ?", String.class, workshop);
    }

    protected long versionOf(UUID workshop) {
        return jdbc.queryForObject(
                "SELECT version FROM workshops WHERE id = ?", Long.class, workshop);
    }

    protected void aiSummarizes(String summary) {
        ai.stubFor(
                post(urlEqualTo(SUMMARIZE))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("{\"summary\":\"" + summary + "\"}")));
    }

    protected void aiAnswers(int status, String body) {
        ai.stubFor(
                post(urlEqualTo(SUMMARIZE))
                        .willReturn(
                                aResponse()
                                        .withStatus(status)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(body)));
    }

    protected int aiCalls() {
        return ai.findAll(postRequestedFor(urlEqualTo(SUMMARIZE))).size();
    }

    /** What the relay would publish for a confirmed brochure. */
    protected void publishRequested(Brochure brochure) {
        publish(
                Topology.SUMMARIZATION_REQUESTED,
                "{\"workshopId\":\""
                        + brochure.workshop()
                        + "\",\"brochureKey\":\""
                        + brochure.key()
                        + "\",\"occurredAt\":\"2026-12-01T09:00:00Z\",\"schemaVersion\":1}");
    }

    protected void publish(String routingKey, String json) {
        var properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setMessageId(UUID.randomUUID().toString());
        rabbit.send(
                Topology.EXCHANGE,
                routingKey,
                MessageBuilder.withBody(json.getBytes(StandardCharsets.UTF_8))
                        .andProperties(properties)
                        .build());
    }

    protected int queueDepth(String queue) {
        var info = amqpAdmin.getQueueInfo(queue);
        return info == null ? 0 : info.getMessageCount();
    }

    protected static final String QUEUE = "unihub.summarization.queue";
    protected static final String PARKING_LOT = "unihub.parking-lot";
}
