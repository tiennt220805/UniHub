package com.unihub.shared.infrastructure.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * M-19/M-23's broker half (messaging.md §2, §4, §6): the topology the application declares exists
 * on a real RabbitMQ with the documented arguments, and each routing key reaches exactly the queue
 * the design says.
 */
@SpringBootTest
class RabbitTopologyIT extends IntegrationTestSupport {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> "i".repeat(48));
    }

    @Autowired private RabbitAdmin admin;
    @Autowired private RabbitTemplate template;
    @Autowired private ObjectMapper json;

    private final HttpClient http = HttpClient.newHttpClient();

    private List<String> allQueues() {
        return Topology.queuesOf(Topology.declarables()).stream()
                .map(org.springframework.amqp.core.Queue::getName)
                .toList();
    }

    /**
     * Empty before as well as after: every audited operation of every earlier class publishes to
     * this shared broker's {@code unihub.audit.queue}, where nothing consumes it, so a count of
     * "exactly one message" starts from whatever those left (232 in the run of 2026-11-04).
     */
    @BeforeEach
    void emptyFirst() {
        admin.initialize();
        allQueues().forEach(admin::purgeQueue);
    }

    @AfterEach
    void purge() {
        allQueues().forEach(admin::purgeQueue);
    }

    private JsonNode management(String path) throws Exception {
        var credentials =
                Base64.getEncoder()
                        .encodeToString(
                                (RABBITMQ.getAdminUsername() + ":" + RABBITMQ.getAdminPassword())
                                        .getBytes(StandardCharsets.UTF_8));
        var response =
                http.send(
                        HttpRequest.newBuilder(URI.create(RABBITMQ.getHttpUrl() + path))
                                .header("Authorization", "Basic " + credentials)
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(path).isEqualTo(200);
        return json.readTree(response.body());
    }

    private void declare() {
        admin.initialize();
    }

    @Test
    @DisplayName("every queue exists on the broker, durable, with the documented arguments")
    void queuesAreDeclaredWithTheirArguments() throws Exception {
        declare();

        for (var expected : Topology.queuesOf(Topology.declarables())) {
            var actual = management("/api/queues/%2F/" + expected.getName());
            assertThat(actual.get("durable").asBoolean()).as(expected.getName()).isTrue();
            expected.getArguments()
                    .forEach(
                            (argument, value) ->
                                    assertThat(actual.get("arguments").get(argument).asText())
                                            .as(expected.getName() + " " + argument)
                                            .isEqualTo(String.valueOf(value)));
        }
    }

    @Test
    @DisplayName("unihub.events is a durable topic exchange")
    void exchangeIsDeclared() throws Exception {
        declare();

        var exchange = management("/api/exchanges/%2F/unihub.events");

        assertThat(exchange.get("type").asText()).isEqualTo("topic");
        assertThat(exchange.get("durable").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("each routing key of messaging.md §2 is delivered to exactly its own queue")
    void routingKeysReachTheirQueue() {
        declare();
        var keyToQueue =
                Map.ofEntries(
                        Map.entry(
                                "unihub.registration.ticket.confirmed",
                                "unihub.notification.queue"),
                        Map.entry("unihub.workshop.event.cancelled", "unihub.notification.queue"),
                        Map.entry("unihub.workshop.event.rescheduled", "unihub.notification.queue"),
                        Map.entry(
                                "unihub.notification.redelivery.requested",
                                "unihub.notification.queue"),
                        Map.entry(
                                "unihub.payment.charge.succeeded",
                                "unihub.payment-confirmation.queue"),
                        Map.entry(
                                "unihub.registration.confirmation.rejected",
                                "unihub.payment-refund.queue"),
                        Map.entry(
                                "unihub.payment.refund.requested",
                                "unihub.payment-refund-execution.queue"),
                        Map.entry(
                                "unihub.registration.hold.reserved",
                                "unihub.registration.hold-delay.queue"),
                        Map.entry(
                                "unihub.registration.hold.expired",
                                "unihub.seat-hold-expiry.queue"),
                        Map.entry(
                                "unihub.workshop.summarization.requested",
                                "unihub.summarization.queue"),
                        Map.entry(
                                "unihub.payment.reconciliation.requested",
                                "unihub.reconciliation.queue"),
                        Map.entry("unihub.system.audit.created", "unihub.audit.queue"));

        keyToQueue.forEach(
                (key, queue) -> {
                    var correlation = new CorrelationData(UUID.randomUUID().toString());
                    template.send("unihub.events", key, message(), correlation);
                    awaitConfirm(correlation);
                    assertThat(correlation.getReturned()).as(key + " is routable").isNull();
                    awaitDepth(queue, 1);
                    purge();
                });
    }

    @Test
    @DisplayName(
            "a key nothing is bound to comes back as returned (mandatory), not silently dropped")
    void unboundKeyIsReturned() {
        declare();
        var correlation = new CorrelationData(UUID.randomUUID().toString());

        template.send("unihub.events", "unihub.nobody.bound.here", message(), correlation);

        awaitConfirm(correlation);
        assertThat(correlation.getReturned()).isNotNull();
        assertThat(correlation.getReturned().getReplyText()).isEqualTo("NO_ROUTE");
    }

    private static Message message() {
        return MessageBuilder.withBody("{}".getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json")
                .build();
    }

    private static void awaitConfirm(CorrelationData correlation) {
        try {
            assertThat(correlation.getFuture().get(10, TimeUnit.SECONDS).isAck()).isTrue();
        } catch (Exception e) {
            throw new AssertionError("no publisher confirm within 10s", e);
        }
    }

    private void awaitDepth(String queue, int expected) {
        Awaitility.await()
                .atMost(10, TimeUnit.SECONDS)
                .untilAsserted(
                        () ->
                                assertThat(admin.getQueueInfo(queue).getMessageCount())
                                        .as(queue)
                                        .isEqualTo(expected));
    }
}
