package com.unihub.shared.infrastructure;

import com.unihub.shared.infrastructure.out.messaging.Topology;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * The RabbitMQ of the audit pipeline ITs (Phase 6 checklist 2.3, 2.6, 2.7), and the properties that
 * point a context at it with every listener running. Not the broker every other class shares: a
 * running audit consumer there would take the audit messages other tests publish (every audited
 * operation publishes one) and write rows for them, and the queue depths other tests count would no
 * longer be theirs alone. One container for the JVM; a test that stops or pauses the broker starts
 * a container of its own.
 */
public final class AuditBroker {

    public static final RabbitMQContainer SHARED =
            new RabbitMQContainer("rabbitmq:3.13.7-management");

    static {
        SHARED.start();
    }

    private AuditBroker() {}

    /** Points the context at {@code broker}, with listeners on and 0.3 s retry stages. */
    public static void register(DynamicPropertyRegistry registry, RabbitMQContainer broker) {
        registry.add("spring.rabbitmq.host", broker::getHost);
        registry.add("spring.rabbitmq.port", broker::getAmqpPort);
        registry.add("spring.rabbitmq.username", broker::getAdminUsername);
        registry.add("spring.rabbitmq.password", broker::getAdminPassword);
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "true");
        registry.add("unihub.messaging.retry-delays", () -> "PT0.3S,PT0.3S,PT0.3S");
    }

    public static void register(DynamicPropertyRegistry registry) {
        register(registry, SHARED);
    }

    /** Empties every queue of the topology; they are declared by the application on first use. */
    public static void purgeAll(RabbitTemplate rabbit, AmqpAdmin admin) {
        rabbit.execute(
                channel -> {
                    channel.queueDeclarePassive(Topology.PARKING_LOT);
                    return null;
                });
        Topology.declarables()
                .getDeclarablesByType(org.springframework.amqp.core.Queue.class)
                .forEach(q -> admin.purgeQueue(q.getName()));
    }
}
