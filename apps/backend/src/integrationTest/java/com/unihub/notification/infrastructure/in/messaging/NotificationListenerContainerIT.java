package com.unihub.notification.infrastructure.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import com.unihub.notification.application.port.in.DispatchNotificationsUseCase;
import com.unihub.notification.domain.exception.NotificationsStillPendingException;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.infrastructure.out.messaging.Topology;
import com.unihub.shared.security.TestJwts;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.DirectFieldAccessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The notification queue's own listener container (Phase 4 checklist D39, task 2.5): two consumer
 * threads and a prefetch of one, <em>and</em> the shared failure handling every other consumer has.
 * Boot attaches that handling (correlation id, republish to the retry chain) only to its default
 * factory, so a factory built by hand without it would let a failed notification message vanish:
 * the business queues have no dead-letter exchange.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest
class NotificationListenerContainerIT extends IntegrationTestSupport {

    private static final String QUEUE = "unihub.notification.queue";
    private static final String CANCELLED = "unihub.workshop.event.cancelled";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> TestJwts.SECRET);
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "true");
    }

    @MockBean private DispatchNotificationsUseCase dispatch;

    @Autowired private RabbitTemplate template;
    @Autowired private RabbitAdmin admin;
    @Autowired private RabbitListenerEndpointRegistry registry;

    @BeforeEach
    void purge() {
        admin.initialize();
        purgeAll();
        reset(dispatch);
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

    private void publish(String type, String body) {
        var message =
                MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
                        .setContentType("application/json")
                        .setMessageId(UUID.randomUUID().toString())
                        .setType(type)
                        .build();
        template.send(Topology.EXCHANGE, CANCELLED, message);
    }

    private static String cancelledBody() {
        return "{\"workshopId\":\""
                + UUID.randomUUID()
                + "\",\"occurredAt\":\"2026-11-01T00:00:00Z\",\"schemaVersion\":1}";
    }

    @Test
    @DisplayName(
            "two consumer threads, one message at a time, so a fan-out cannot hold the queue (D39)")
    void container_hasTwoThreadsAndPrefetchOne() {
        var container =
                registry.getListenerContainers().stream()
                        .map(SimpleMessageListenerContainer.class::cast)
                        .filter(c -> Arrays.asList(c.getQueueNames()).contains(QUEUE))
                        .findFirst()
                        .orElseThrow();
        var fields = new DirectFieldAccessor(container);

        assertThat(container.isRunning()).isTrue();
        assertThat(fields.getPropertyValue("concurrentConsumers")).isEqualTo(2);
        assertThat(fields.getPropertyValue("maxConcurrentConsumers")).isEqualTo(2);
        assertThat(fields.getPropertyValue("prefetchCount")).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "a transient failure enters the 5s retry stage instead of being dropped (the shared handler is wired)")
    void transientFailure_goesToTheRetryChain() {
        doThrow(new NotificationsStillPendingException("1 row still PENDING"))
                .when(dispatch)
                .dispatch(any());

        publish(CANCELLED, cancelledBody());

        var stage = Topology.retryStageQueues(QUEUE).getFirst();
        var retried =
                Awaitility.await()
                        .atMost(Duration.ofSeconds(4))
                        .until(() -> template.receive(stage, 200), m -> m != null);
        assertThat(retried.getMessageProperties().getType()).isEqualTo(CANCELLED);
        assertThat(retried.getMessageProperties().<String>getHeader("x-original-routing-key"))
                .isEqualTo(CANCELLED);
        assertThat(template.receive(Topology.PARKING_LOT, 300)).isNull();
    }

    @Test
    @DisplayName(
            "a permanent failure is parked at once, with its type and message-id intact (AC34.2)")
    void permanentFailure_isParked() {
        publish("unihub.payment.charge.succeeded", cancelledBody());

        var parked =
                Awaitility.await()
                        .atMost(Duration.ofSeconds(10))
                        .until(() -> template.receive(Topology.PARKING_LOT, 200), m -> m != null);

        assertThat(parked.getMessageProperties().getType())
                .isEqualTo("unihub.payment.charge.succeeded");
        assertThat(parked.getMessageProperties().getMessageId()).isNotBlank();
        assertThat(parked.getMessageProperties().<String>getHeader("x-exception-class"))
                .contains("NonRetryableMessageException");
    }

    @Test
    @DisplayName("a good message reaches the use case")
    void goodMessage_reachesTheUseCase() {
        publish(CANCELLED, cancelledBody());

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> verify(dispatch).dispatch(any()));
    }
}
