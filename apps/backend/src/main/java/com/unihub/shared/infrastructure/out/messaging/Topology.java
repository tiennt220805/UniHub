package com.unihub.shared.infrastructure.out.messaging;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;

/**
 * The RabbitMQ topology of messaging.md §2 and §4, as data: one durable topic exchange, the
 * business queues and their bindings, a dedicated three-stage retry chain per consumed queue, and
 * the single shared parking lot. Queue arguments cannot change once a queue exists, so a change
 * here is a new queue, not an edit.
 *
 * <p>Retry stages are reached by an application-level republish through the default exchange (the
 * routing key is the stage queue's own name). Each stage dead-letters back to its business queue
 * through a fixed {@code x-dead-letter-routing-key} that is bound to that queue, so the key the
 * message originally arrived with no longer matters.
 */
public final class Topology {

    public static final String EXCHANGE = "unihub.events";
    public static final String PARKING_LOT = "unihub.parking-lot";

    /** The stage names are fixed by messaging.md §4; only a test ever changes the delays. */
    public static final List<String> RETRY_STAGES = List.of("5s", "30s", "5m");

    public static final List<Duration> RETRY_DELAYS =
            List.of(Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(5));

    /**
     * Seat-hold window (docs/requirement.md §1.3.1): exactly 15 minutes. The production value of
     * {@code unihub.registration.seat-hold}, which is the one source of the number: it sets {@code
     * hold_expires_at} and, through {@link #declarables(Duration, List)}, the delay queue's TTL.
     */
    public static final Duration HOLD_WINDOW = Duration.ofMinutes(15);

    // Routing keys (messaging.md §2), copied verbatim.
    public static final String TICKET_CONFIRMED = "unihub.registration.ticket.confirmed";
    public static final String WORKSHOP_CANCELLED = "unihub.workshop.event.cancelled";
    public static final String WORKSHOP_RESCHEDULED = "unihub.workshop.event.rescheduled";
    public static final String CHARGE_SUCCEEDED = "unihub.payment.charge.succeeded";
    public static final String CONFIRMATION_REJECTED = "unihub.registration.confirmation.rejected";
    public static final String REFUND_REQUESTED = "unihub.payment.refund.requested";
    public static final String NOTIFICATION_REDELIVERY = "unihub.notification.redelivery.requested";
    public static final String HOLD_RESERVED = "unihub.registration.hold.reserved";
    public static final String HOLD_EXPIRED = "unihub.registration.hold.expired";
    public static final String SUMMARIZATION_REQUESTED = "unihub.workshop.summarization.requested";
    public static final String RECONCILIATION_REQUESTED = "unihub.payment.reconciliation.requested";
    public static final String AUDIT_CREATED = "unihub.system.audit.created";

    public static final String HOLD_DELAY_QUEUE = "unihub.registration.hold-delay.queue";
    public static final String AUDIT_QUEUE = "unihub.audit.queue";

    /**
     * Length bound of {@link #AUDIT_QUEUE}, the only business queue that has one (messaging.md §3,
     * Phase 6 checklist D70). The production value of {@code unihub.audit.queue-max-length}; a test
     * shrinks it to reach the overflow in a few messages.
     */
    public static final int AUDIT_QUEUE_MAX_LENGTH = 100_000;

    /**
     * A queue with a consumer, and the fixed key its retry stages dead-letter back with. That key
     * is one of the queue's own bindings, except for the notification queue, which has three real
     * keys and needs one dedicated key for its chain (messaging.md §2, §4).
     *
     * <p>{@code waitsForDependency} is the one exception to "three retries, then the parking lot"
     * (messaging.md §4, checklist D19, D25): a failure that says a dependency is down, not that the
     * message is bad ({@link com.unihub.shared.domain.RetryUntilAvailable}), stays in the chain at
     * its last stage for as long as it takes. It is per queue, so the same exception on another
     * queue is parked as usual.
     */
    record ConsumedQueue(
            String name,
            List<String> bindingKeys,
            String retryDeadLetterKey,
            boolean waitsForDependency) {

        ConsumedQueue(String name, List<String> bindingKeys, String retryDeadLetterKey) {
            this(name, bindingKeys, retryDeadLetterKey, false);
        }
    }

    static final List<ConsumedQueue> CONSUMED_QUEUES =
            List.of(
                    new ConsumedQueue(
                            "unihub.notification.queue",
                            List.of(
                                    TICKET_CONFIRMED,
                                    WORKSHOP_CANCELLED,
                                    WORKSHOP_RESCHEDULED,
                                    NOTIFICATION_REDELIVERY),
                            NOTIFICATION_REDELIVERY),
                    new ConsumedQueue(
                            "unihub.payment-confirmation.queue",
                            List.of(CHARGE_SUCCEEDED),
                            CHARGE_SUCCEEDED),
                    new ConsumedQueue(
                            "unihub.payment-refund.queue",
                            List.of(CONFIRMATION_REJECTED),
                            CONFIRMATION_REJECTED),
                    new ConsumedQueue(
                            "unihub.payment-refund-execution.queue",
                            List.of(REFUND_REQUESTED),
                            REFUND_REQUESTED,
                            true),
                    new ConsumedQueue(
                            "unihub.seat-hold-expiry.queue",
                            List.of(HOLD_EXPIRED),
                            HOLD_EXPIRED,
                            true),
                    new ConsumedQueue(
                            "unihub.summarization.queue",
                            List.of(SUMMARIZATION_REQUESTED),
                            SUMMARIZATION_REQUESTED),
                    new ConsumedQueue(
                            "unihub.reconciliation.queue",
                            List.of(RECONCILIATION_REQUESTED),
                            RECONCILIATION_REQUESTED),
                    new ConsumedQueue(AUDIT_QUEUE, List.of(AUDIT_CREATED), AUDIT_CREATED));

    private Topology() {}

    /** Whether a failure that names a down dependency stays in this queue's chain, unparked. */
    public static boolean waitsForDependency(String businessQueue) {
        return CONSUMED_QUEUES.stream()
                .anyMatch(q -> q.name().equals(businessQueue) && q.waitsForDependency());
    }

    /** {@code unihub.notification.queue} → {@code unihub.notification.retry.5s}, {@code .30s}, … */
    public static List<String> retryStageQueues(String businessQueue) {
        var base =
                businessQueue.endsWith(".queue")
                        ? businessQueue.substring(0, businessQueue.length() - ".queue".length())
                        : businessQueue;
        return RETRY_STAGES.stream().map(stage -> base + ".retry." + stage).toList();
    }

    /** Everything the broker must hold, with the production hold window and retry delays. */
    public static Declarables declarables() {
        return declarables(HOLD_WINDOW, RETRY_DELAYS);
    }

    /**
     * Everything the broker must hold for the application to publish and consume. The two values
     * are configuration only so that a test can run the 15-minute hold and the 5m35s chain in
     * seconds; queue arguments cannot change once declared, so a changed value on a broker that
     * already holds the queues means declaring new ones.
     */
    public static Declarables declarables(Duration holdWindow, List<Duration> retryDelays) {
        return declarables(holdWindow, retryDelays, AUDIT_QUEUE_MAX_LENGTH);
    }

    /**
     * As {@link #declarables(Duration, List)}, with the length bound of the audit queue. When it is
     * full the broker refuses the publish (the publisher counts it as a nack) and dead-letters both
     * the refused message and a message returning from the audit retry chain to the parking lot,
     * which alerts: no overflow is lost silently (messaging.md §3).
     */
    public static Declarables declarables(
            Duration holdWindow, List<Duration> retryDelays, int auditQueueMaxLength) {
        var all = new ArrayList<Declarable>();
        var exchange = new TopicExchange(EXCHANGE, true, false);
        all.add(exchange);
        all.add(QueueBuilder.durable(PARKING_LOT).build());

        for (var consumed : CONSUMED_QUEUES) {
            var queue = businessQueue(consumed.name(), auditQueueMaxLength);
            all.add(queue);
            for (var key : consumed.bindingKeys()) {
                all.add(BindingBuilder.bind(queue).to(exchange).with(key));
            }
            all.addAll(
                    retryChain(consumed.name(), consumed.retryDeadLetterKey(), retryDelays)
                            .getDeclarables());
        }

        // Delay-only queue: no consumer; a message leaves it only by expiring, and is dead-lettered
        // back to the exchange as hold.expired (registration.md §3).
        var holdDelay =
                QueueBuilder.durable(HOLD_DELAY_QUEUE)
                        .ttl((int) holdWindow.toMillis())
                        .deadLetterExchange(EXCHANGE)
                        .deadLetterRoutingKey(HOLD_EXPIRED)
                        .build();
        all.add(holdDelay);
        all.add(BindingBuilder.bind(holdDelay).to(exchange).with(HOLD_RESERVED));
        return new Declarables(all);
    }

    /**
     * A business queue has no arguments, so a failure can only leave it through the application
     * republish. The audit queue is the exception: bounded, with the parking lot as dead-letter
     * target through the default exchange ({@code ""}), which routes by queue name.
     */
    private static Queue businessQueue(String name, int auditQueueMaxLength) {
        var builder = QueueBuilder.durable(name);
        if (AUDIT_QUEUE.equals(name)) {
            builder.maxLength(auditQueueMaxLength)
                    // Spring AMQP 3.1's Overflow enum has no reject-publish-dlx yet.
                    .withArgument("x-overflow", "reject-publish-dlx")
                    .deadLetterExchange("")
                    .deadLetterRoutingKey(PARKING_LOT);
        }
        return builder.build();
    }

    /**
     * The retry stages of one business queue, in order. Each stage holds a message for its delay,
     * then dead-letters it to {@code deadLetterKey} on the main exchange.
     */
    public static Declarables retryChain(
            String businessQueue, String deadLetterKey, List<Duration> delays) {
        var stages = retryStageQueues(businessQueue);
        var declared = new ArrayList<Declarable>();
        for (int i = 0; i < stages.size(); i++) {
            Queue stage =
                    QueueBuilder.durable(stages.get(i))
                            .ttl((int) delays.get(i).toMillis())
                            .deadLetterExchange(EXCHANGE)
                            .deadLetterRoutingKey(deadLetterKey)
                            .build();
            declared.add(stage);
        }
        return new Declarables(declared);
    }

    static List<Binding> bindingsOf(Declarables declarables) {
        return declarables.getDeclarablesByType(Binding.class);
    }

    static List<Queue> queuesOf(Declarables declarables) {
        return declarables.getDeclarablesByType(Queue.class);
    }

    static List<TopicExchange> exchangesOf(Declarables declarables) {
        return declarables.getDeclarablesByType(TopicExchange.class);
    }
}
