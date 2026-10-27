package com.unihub.shared.infrastructure.out.messaging;

import com.unihub.shared.domain.RetryUntilAvailable;
import com.unihub.shared.infrastructure.config.MessagingProperties;
import com.unihub.shared.messaging.NonRetryableMessageException;
import com.unihub.shared.messaging.ParkedMessageObserver;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;

/**
 * The shared consumer error handler (backend-coding-standards.md §8.6, messaging.md §4). Business
 * queues have no dead-letter exchange, so a failed message is <em>republished</em> by this class:
 * to the next retry stage of its own queue, or to {@code unihub.parking-lot} once the three stages
 * are used up or the failure is permanent.
 *
 * <p>One exception to the three retries (checklist D19, D25): on a queue that waits for its
 * dependency, a failure marked {@link RetryUntilAvailable} is republished to the last stage again
 * and again instead of being parked.
 *
 * <p>Called once per failed delivery, in place of a broker reject. Returning normally acks the
 * original, which is safe only because the republish was confirmed first; if it cannot be
 * confirmed, the original is requeued instead, so a failure here never loses the message.
 *
 * <p>The number of retries so far is read from RabbitMQ's {@code x-death} header: one entry per
 * (queue, reason), each retry stage being its own queue, so the counts of the entries belonging to
 * this queue's chain are summed.
 */
public final class RetryChainRecoverer implements MessageRecoverer {

    public static final String ORIGINAL_ROUTING_KEY_HEADER = "x-original-routing-key";
    public static final String ORIGINAL_EXCHANGE_HEADER = "x-original-exchange";
    public static final String ORIGINAL_QUEUE_HEADER = "x-original-queue";
    public static final String EXCEPTION_MESSAGE_HEADER = "x-exception-message";
    public static final String EXCEPTION_CLASS_HEADER = "x-exception-class";

    private static final Logger LOG = LoggerFactory.getLogger(RetryChainRecoverer.class);

    /** The default exchange routes by queue name. */
    private static final String DEFAULT_EXCHANGE = "";

    private final ConfirmedSender sender;
    private final List<ParkedMessageObserver> observers;

    public RetryChainRecoverer(RabbitTemplate template, MessagingProperties properties) {
        this(template, properties, List.of());
    }

    public RetryChainRecoverer(
            RabbitTemplate template,
            MessagingProperties properties,
            List<ParkedMessageObserver> observers) {
        this.sender = new ConfirmedSender(template, properties.confirmTimeout());
        this.observers = List.copyOf(observers);
    }

    @Override
    public void recover(Message message, Throwable cause) {
        var properties = message.getMessageProperties();
        var queue = properties.getConsumerQueue();
        var failure = rootOf(cause);
        rememberOrigin(properties);

        if (isPermanent(cause)) {
            LOG.warn(
                    "Message {} from {} is permanently unprocessable, parking it: {}",
                    properties.getMessageId(),
                    queue,
                    failure.toString());
            park(message, failure);
            return;
        }

        var stages = Topology.retryStageQueues(queue);
        int retriesSoFar = retriesSoFar(properties, stages);
        if (retriesSoFar >= stages.size() && waitsForDependency(queue, cause)) {
            // The dependency is down, not the message bad: stay at the last stage, never park
            // (messaging.md §4, checklist D19, D25).
            var last = stages.getLast();
            LOG.warn(
                    "Message {} from {} is still waiting for its dependency (attempt {}), retrying"
                            + " via {}: {}",
                    properties.getMessageId(),
                    queue,
                    retriesSoFar + 1,
                    last,
                    failure.toString());
            republish(message, last);
            return;
        }
        if (retriesSoFar >= stages.size()) {
            LOG.warn(
                    "Message {} from {} failed after {} retries, parking it: {}",
                    properties.getMessageId(),
                    queue,
                    retriesSoFar,
                    failure.toString());
            park(message, failure);
            return;
        }
        var stage = stages.get(retriesSoFar);
        LOG.warn(
                "Message {} from {} failed (retry {} of {}), retrying via {}: {}",
                properties.getMessageId(),
                queue,
                retriesSoFar + 1,
                stages.size(),
                stage,
                failure.toString());
        republish(message, stage);
    }

    /** Sum of the {@code x-death} counts of this chain's own stage queues. */
    static int retriesSoFar(MessageProperties properties, List<String> stageQueues) {
        List<Map<String, ?>> deaths = properties.getXDeathHeader();
        if (deaths == null) {
            return 0;
        }
        long retries = 0;
        for (var death : deaths) {
            if (stageQueues.contains(death.get("queue"))
                    && death.get("count") instanceof Number n) {
                retries += n.longValue();
            }
        }
        return (int) retries;
    }

    /**
     * Where the message first arrived. After a retry hop the received routing key is the stage's
     * fixed dead-letter key, so the original is captured on the first failure and carried along.
     */
    private static void rememberOrigin(MessageProperties properties) {
        if (properties.getHeader(ORIGINAL_ROUTING_KEY_HEADER) == null) {
            properties.setHeader(ORIGINAL_ROUTING_KEY_HEADER, properties.getReceivedRoutingKey());
            properties.setHeader(ORIGINAL_EXCHANGE_HEADER, properties.getReceivedExchange());
            properties.setHeader(ORIGINAL_QUEUE_HEADER, properties.getConsumerQueue());
        }
    }

    private void park(Message message, Throwable failure) {
        var properties = message.getMessageProperties();
        properties.setHeader(EXCEPTION_MESSAGE_HEADER, String.valueOf(failure.getMessage()));
        properties.setHeader(EXCEPTION_CLASS_HEADER, failure.getClass().getName());
        republish(message, Topology.PARKING_LOT);
        // Only once the parked copy is confirmed: a republish that failed requeues the original and
        // nothing is parked yet.
        var queue = properties.getConsumerQueue();
        for (var observer : observers) {
            try {
                observer.parked(queue);
            } catch (RuntimeException e) {
                LOG.warn("A parking observer failed for {}: {}", queue, e.toString());
            }
        }
    }

    private void republish(Message message, String queue) {
        try {
            sender.send(DEFAULT_EXCHANGE, queue, message);
        } catch (RuntimeException e) {
            // Not rejected: with no dead-letter exchange a reject would drop the message.
            throw new ImmediateRequeueAmqpException(
                    "Could not republish to " + queue + "; requeueing the original", e);
        }
    }

    /**
     * Whether the failure says a dependency is down AND this queue is one that waits for it. Both
     * are needed: the same exception on another queue (the gateway is also called by the
     * reconciliation consumer) follows the three-attempt rule.
     */
    static boolean waitsForDependency(String queue, Throwable cause) {
        if (!Topology.waitsForDependency(queue)) {
            return false;
        }
        for (Throwable t = cause; t != null; t = t.getCause()) {
            if (t instanceof RetryUntilAvailable) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private static boolean isPermanent(Throwable cause) {
        for (Throwable t = cause; t != null; t = t.getCause()) {
            if (t instanceof NonRetryableMessageException
                    || t instanceof MessageConversionException
                    || t
                            instanceof
                            org.springframework.messaging.converter.MessageConversionException
                    || t instanceof MethodArgumentNotValidException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /** The listener's own exception, under Spring's wrappers. */
    private static Throwable rootOf(Throwable cause) {
        var current = cause;
        while (current.getCause() != null
                && current.getCause() != current
                && (current
                                instanceof
                                org.springframework.amqp.rabbit.support
                                        .ListenerExecutionFailedException
                        || current instanceof org.springframework.messaging.MessagingException)) {
            current = current.getCause();
        }
        return current;
    }
}
