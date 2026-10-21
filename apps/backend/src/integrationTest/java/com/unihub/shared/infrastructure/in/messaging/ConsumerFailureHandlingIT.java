package com.unihub.shared.infrastructure.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.infrastructure.out.messaging.Topology;
import com.unihub.shared.messaging.NonRetryableMessageException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * M-19/M-23's consumer half, AC2.5 (messaging.md §4, backend-coding-standards.md §8.5–§8.6) against
 * a real RabbitMQ, through the real listener container and the real shared error handler: a
 * transient failure is retried exactly three times and then parked; a permanent one is parked at
 * once; nothing is ever dropped. The test queue gets the production retry chain shape with
 * sub-second stage delays, so the whole walk takes about a second instead of 5m35s.
 */
// Its listeners are running, so the context is closed when the class ends (see
// IntegrationTestSupport).
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest
@Import(ConsumerFailureHandlingIT.Probe.class)
class ConsumerFailureHandlingIT extends IntegrationTestSupport {

    static final String QUEUE = "unihub.itest-probe.queue";
    static final String ARRIVAL_KEY = "unihub.itest.probe.arrived";
    static final String REDELIVERY_KEY = "unihub.itest.probe.redelivery";

    /** A message the probe listener can be told to fail on. */
    record Command(String id, String mode) {}

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> "i".repeat(48));
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "true");
    }

    /**
     * A queue shaped like a production one: bound to a real key and to a dedicated redelivery key
     * that its retry stages dead-letter back with, so after a retry hop the received routing key is
     * not the one the message arrived with.
     */
    @TestConfiguration
    static class Probe {

        @Bean
        Declarables probeTopology() {
            var exchange = new TopicExchange(Topology.EXCHANGE, true, false);
            Queue queue = QueueBuilder.durable(QUEUE).build();
            Binding arrival = BindingBuilder.bind(queue).to(exchange).with(ARRIVAL_KEY);
            Binding redelivery = BindingBuilder.bind(queue).to(exchange).with(REDELIVERY_KEY);
            var chain =
                    Topology.retryChain(
                            QUEUE,
                            REDELIVERY_KEY,
                            List.of(
                                    Duration.ofMillis(300),
                                    Duration.ofMillis(300),
                                    Duration.ofMillis(300)));
            var all = new java.util.ArrayList<org.springframework.amqp.core.Declarable>();
            all.add(queue);
            all.add(arrival);
            all.add(redelivery);
            all.addAll(chain.getDeclarables());
            return new Declarables(all);
        }

        @Bean
        ProbeListener probeListener() {
            return new ProbeListener();
        }
    }

    @Component
    static class ProbeListener {

        final List<String> deliveries = new CopyOnWriteArrayList<>();
        final List<String> receivedKeys = new CopyOnWriteArrayList<>();
        final AtomicReference<String> correlationSeen = new AtomicReference<>("unset");

        @RabbitListener(queues = QUEUE)
        void on(Command command, @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String receivedKey) {
            deliveries.add(command.id());
            receivedKeys.add(receivedKey);
            correlationSeen.set(String.valueOf(MDC.get("correlationId")));
            switch (command.mode()) {
                case "fail" -> throw new IllegalStateException("boom " + command.id());
                case "permanent" -> throw new NonRetryableMessageException("unsupported version");
                case "failOnce" -> {
                    if (deliveries.stream().filter(command.id()::equals).count() == 1) {
                        throw new IllegalStateException("first attempt fails");
                    }
                }
                default -> {
                    // success
                }
            }
        }

        long deliveriesOf(String id) {
            return deliveries.stream().filter(id::equals).count();
        }
    }

    @Autowired private RabbitTemplate template;
    @Autowired private RabbitAdmin admin;
    @Autowired private ProbeListener listener;

    @BeforeEach
    void declareAndClean() {
        admin.initialize();
        purgeAll();
        listener.deliveries.clear();
        listener.receivedKeys.clear();
    }

    @AfterEach
    void clean() {
        purgeAll();
    }

    private void purgeAll() {
        admin.purgeQueue(QUEUE);
        admin.purgeQueue(Topology.PARKING_LOT);
        Topology.retryStageQueues(QUEUE).forEach(admin::purgeQueue);
    }

    private static Message json(String body) {
        return MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json")
                .build();
    }

    private static String command(String id, String mode) {
        return "{\"id\":\"" + id + "\",\"mode\":\"" + mode + "\"}";
    }

    private Message awaitParked() {
        return Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .until(() -> template.receive(Topology.PARKING_LOT, 200), m -> m != null);
    }

    private void assertNothingParked() {
        assertThat(template.receive(Topology.PARKING_LOT, 500)).isNull();
    }

    @Test
    @DisplayName("a transient failure is retried exactly three times, then parked with its origin")
    void alwaysFailing_isRetriedThreeTimesThenParked() {
        template.send(Topology.EXCHANGE, ARRIVAL_KEY, json(command("m1", "fail")));

        var parked = awaitParked();

        assertThat(listener.deliveriesOf("m1")).as("first delivery + 3 retries").isEqualTo(4);
        var headers = parked.getMessageProperties();
        assertThat(headers.<String>getHeader("x-original-routing-key")).isEqualTo(ARRIVAL_KEY);
        assertThat(headers.<String>getHeader("x-original-queue")).isEqualTo(QUEUE);
        assertThat(headers.<String>getHeader("x-exception-message")).isEqualTo("boom m1");
        assertThat(new String(parked.getBody(), StandardCharsets.UTF_8)).contains("\"m1\"");
        assertThat(template.receive(QUEUE, 300)).as("nothing left on the business queue").isNull();
    }

    @Test
    @DisplayName("a permanent failure is parked on the first delivery, with no retry")
    void permanentFailure_isParkedAtOnce() {
        template.send(Topology.EXCHANGE, ARRIVAL_KEY, json(command("m2", "permanent")));

        var parked = awaitParked();

        assertThat(listener.deliveriesOf("m2")).isEqualTo(1);
        assertThat(parked.getMessageProperties().<String>getHeader("x-exception-message"))
                .isEqualTo("unsupported version");
        assertThat(parked.getMessageProperties().<String>getHeader("x-original-routing-key"))
                .isEqualTo(ARRIVAL_KEY);
    }

    @Test
    @DisplayName("an unparseable payload is parked without ever reaching the listener")
    void unparseablePayload_isParkedWithoutInvokingTheListener() {
        template.send(Topology.EXCHANGE, ARRIVAL_KEY, json("this is {not json"));

        var parked = awaitParked();

        assertThat(listener.deliveries).isEmpty();
        assertThat(new String(parked.getBody(), StandardCharsets.UTF_8))
                .isEqualTo("this is {not json");
        assertThat(parked.getMessageProperties().<String>getHeader("x-original-routing-key"))
                .isEqualTo(ARRIVAL_KEY);
    }

    @Test
    @DisplayName("a failure that clears on the retry is processed once more and never parked")
    void failsOnce_succeedsOnRetry() {
        template.send(Topology.EXCHANGE, ARRIVAL_KEY, json(command("m3", "failOnce")));

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(listener.deliveriesOf("m3")).isEqualTo(2));

        assertNothingParked();
        assertThat(template.receive(QUEUE, 300)).isNull();
    }

    @Test
    @DisplayName("a successful message is consumed once and leaves nothing behind")
    void success_isConsumedOnce() {
        template.send(Topology.EXCHANGE, ARRIVAL_KEY, json(command("m4", "ok")));

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(listener.deliveriesOf("m4")).isEqualTo(1));

        assertNothingParked();
    }

    @Test
    @DisplayName(
            "the original routing key survives the retry hops, which dead-letter with another key")
    void originalRoutingKey_survivesTheRetryChain() {
        template.send(Topology.EXCHANGE, ARRIVAL_KEY, json(command("m5", "fail")));

        var parked = awaitParked();

        assertThat(listener.receivedKeys)
                .as("first delivery by the real key, each retry by the stage's fixed key")
                .containsExactly(ARRIVAL_KEY, REDELIVERY_KEY, REDELIVERY_KEY, REDELIVERY_KEY);
        assertThat(parked.getMessageProperties().<String>getHeader("x-original-routing-key"))
                .isEqualTo(ARRIVAL_KEY);
    }

    @Test
    @DisplayName(
            "the listener sees the message's correlation id in the MDC, and it is cleared after")
    void correlationId_isRestoredForTheListenerAndClearedAfter() {
        var withCorrelation =
                MessageBuilder.withBody(command("m6", "ok").getBytes(StandardCharsets.UTF_8))
                        .setContentType("application/json")
                        .setHeader("correlationId", "4bf92f3577b34da6a3ce929d0e0e4736")
                        .build();
        template.send(Topology.EXCHANGE, ARRIVAL_KEY, withCorrelation);
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(listener.deliveriesOf("m6")).isEqualTo(1));
        assertThat(listener.correlationSeen.get()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");

        template.send(Topology.EXCHANGE, ARRIVAL_KEY, json(command("m7", "ok")));
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(listener.deliveriesOf("m7")).isEqualTo(1));

        assertThat(listener.correlationSeen.get())
                .as("a message without the header must not inherit the previous one's id")
                .isEqualTo("null");
    }
}
