package com.unihub.shared.infrastructure.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.unihub.shared.domain.RetryUntilAvailable;
import com.unihub.shared.infrastructure.config.MessagingProperties;
import com.unihub.shared.messaging.NonRetryableMessageException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;
import org.springframework.amqp.support.converter.MessageConversionException;

class RetryChainRecovererTest {

    private static final String QUEUE = "unihub.seat-hold-expiry.queue";

    private final RabbitTemplate template = mock(RabbitTemplate.class);
    private final RetryChainRecoverer recoverer =
            new RetryChainRecoverer(
                    template,
                    new MessagingProperties(Duration.ofSeconds(1), Topology.RETRY_DELAYS));

    /** Every send is acked and routed, unless a test overrides it. */
    @BeforeEach
    void confirmEverySend() {
        doAnswer(
                        invocation -> {
                            CorrelationData correlation = invocation.getArgument(3);
                            correlation
                                    .getFuture()
                                    .complete(new CorrelationData.Confirm(true, null));
                            return null;
                        })
                .when(template)
                .send(
                        any(String.class),
                        any(String.class),
                        any(Message.class),
                        any(CorrelationData.class));
    }

    private Message failedMessage(List<Map<String, ?>> xDeath) {
        return failedMessageOn(QUEUE, xDeath);
    }

    private Message failedMessageOn(String queue, List<Map<String, ?>> xDeath) {
        var properties = new MessageProperties();
        properties.setConsumerQueue(queue);
        properties.setReceivedExchange("unihub.events");
        properties.setReceivedRoutingKey("unihub.registration.hold.expired");
        properties.setMessageId("11111111-1111-1111-1111-111111111111");
        if (xDeath != null) {
            properties.setHeader("x-death", xDeath);
        }
        return new Message("{}".getBytes(), properties);
    }

    private static Map<String, Object> death(String queue, long count) {
        return Map.of("queue", queue, "reason", "expired", "count", count);
    }

    private Message sentTo(String routingKey) {
        var sent = ArgumentCaptor.forClass(Message.class);
        verify(template).send(eq(""), eq(routingKey), sent.capture(), any(CorrelationData.class));
        return sent.getValue();
    }

    @Test
    @DisplayName("the first failure goes to the 5s stage of this queue's own chain")
    void firstFailure_goesToTheFirstStage() {
        recoverer.recover(failedMessage(null), new IllegalStateException("boom"));

        sentTo("unihub.seat-hold-expiry.retry.5s");
    }

    @Test
    @DisplayName("each further failure moves one stage along: 30s, then 5m")
    void furtherFailures_walkTheChain() {
        recoverer.recover(
                failedMessage(List.of(death("unihub.seat-hold-expiry.retry.5s", 1))),
                new IllegalStateException("boom"));
        sentTo("unihub.seat-hold-expiry.retry.30s");
    }

    @Test
    @DisplayName("the third retry stage is 5m")
    void thirdStage() {
        recoverer.recover(
                failedMessage(
                        List.of(
                                death("unihub.seat-hold-expiry.retry.5s", 1),
                                death("unihub.seat-hold-expiry.retry.30s", 1))),
                new IllegalStateException("boom"));

        sentTo("unihub.seat-hold-expiry.retry.5m");
    }

    @Test
    @DisplayName("after three retries the message goes to the parking lot, not a fourth stage")
    void exhausted_goesToTheParkingLot() {
        recoverer.recover(
                failedMessage(
                        List.of(
                                death("unihub.seat-hold-expiry.retry.5s", 1),
                                death("unihub.seat-hold-expiry.retry.30s", 1),
                                death("unihub.seat-hold-expiry.retry.5m", 1))),
                new IllegalStateException("last failure"));

        var parked = sentTo("unihub.parking-lot");
        assertThat(parked.getMessageProperties().<String>getHeader("x-exception-message"))
                .isEqualTo("last failure");
    }

    @Test
    @DisplayName("an x-death entry from another queue is not counted as a retry")
    void foreignDeathEntries_areIgnored() {
        // A message that waited in the hold-delay queue already carries an "expired" entry for it.
        recoverer.recover(
                failedMessage(List.of(death("unihub.registration.hold-delay.queue", 1))),
                new IllegalStateException("boom"));

        sentTo("unihub.seat-hold-expiry.retry.5s");
    }

    @Test
    @DisplayName("retries are counted across the chain's three queues, not from a single entry")
    void retriesAreSummedAcrossStages() {
        var properties =
                failedMessage(
                                List.of(
                                        death("unihub.seat-hold-expiry.retry.5s", 1),
                                        death("unihub.seat-hold-expiry.retry.30s", 1)))
                        .getMessageProperties();

        assertThat(RetryChainRecoverer.retriesSoFar(properties, Topology.retryStageQueues(QUEUE)))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("no x-death header means no retries yet")
    void noXDeath_isZero() {
        assertThat(
                        RetryChainRecoverer.retriesSoFar(
                                failedMessage(null).getMessageProperties(),
                                Topology.retryStageQueues(QUEUE)))
                .isZero();
    }

    @Test
    @DisplayName("a NonRetryableMessageException skips the chain and parks the message at once")
    void nonRetryable_parksImmediately() {
        recoverer.recover(
                failedMessage(null),
                new ListenerExecutionFailedException(
                        "listener failed",
                        new NonRetryableMessageException("unsupported schemaVersion 9"),
                        failedMessage(null)));

        var parked = sentTo("unihub.parking-lot");
        assertThat(parked.getMessageProperties().<String>getHeader("x-exception-message"))
                .isEqualTo("unsupported schemaVersion 9");
        assertThat(parked.getMessageProperties().<String>getHeader("x-exception-class"))
                .isEqualTo(NonRetryableMessageException.class.getName());
    }

    @Test
    @DisplayName("a payload that cannot be converted is permanent: parked, never retried")
    void conversionFailure_parksImmediately() {
        recoverer.recover(
                failedMessage(null),
                new ListenerExecutionFailedException(
                        "conversion",
                        new MessageConversionException("not json"),
                        failedMessage(null)));

        sentTo("unihub.parking-lot");
    }

    @Test
    @DisplayName("the parked message names the routing key it first arrived with")
    void parkedMessage_carriesTheOriginalRoutingKey() {
        recoverer.recover(failedMessage(null), new NonRetryableMessageException("bad"));

        var parked = sentTo("unihub.parking-lot");
        assertThat(parked.getMessageProperties().<String>getHeader("x-original-routing-key"))
                .isEqualTo("unihub.registration.hold.expired");
        assertThat(parked.getMessageProperties().<String>getHeader("x-original-queue"))
                .isEqualTo(QUEUE);
    }

    @Test
    @DisplayName("an original routing key already recorded on an earlier hop is not overwritten")
    void originalRoutingKey_survivesRetryHops() {
        var message = failedMessage(List.of(death("unihub.seat-hold-expiry.retry.5s", 1)));
        message.getMessageProperties().setHeader("x-original-routing-key", "the.real.key");
        message.getMessageProperties().setReceivedRoutingKey("unihub.registration.hold.expired");

        recoverer.recover(message, new NonRetryableMessageException("bad"));

        assertThat(
                        sentTo("unihub.parking-lot")
                                .getMessageProperties()
                                .<String>getHeader("x-original-routing-key"))
                .isEqualTo("the.real.key");
    }

    @Test
    @DisplayName("if the republish is not confirmed the original is requeued, never lost")
    void republishFailure_requeuesTheOriginal() {
        doThrow(new AmqpConnectException(new java.net.ConnectException("refused")))
                .when(template)
                .send(
                        any(String.class),
                        any(String.class),
                        any(Message.class),
                        any(CorrelationData.class));

        assertThatThrownBy(
                        () ->
                                recoverer.recover(
                                        failedMessage(null), new IllegalStateException("x")))
                .isInstanceOf(ImmediateRequeueAmqpException.class);
    }

    @Test
    @DisplayName("a nacked republish is also requeued")
    void nackedRepublish_requeuesTheOriginal() {
        doAnswer(
                        invocation -> {
                            CorrelationData correlation = invocation.getArgument(3);
                            correlation
                                    .getFuture()
                                    .complete(new CorrelationData.Confirm(false, "disk full"));
                            return null;
                        })
                .when(template)
                .send(
                        any(String.class),
                        any(String.class),
                        any(Message.class),
                        any(CorrelationData.class));

        assertThatThrownBy(
                        () ->
                                recoverer.recover(
                                        failedMessage(null), new IllegalStateException("x")))
                .isInstanceOf(ImmediateRequeueAmqpException.class);
    }

    @Test
    @DisplayName("an unroutable republish (returned) is also requeued")
    void returnedRepublish_requeuesTheOriginal() {
        doAnswer(
                        invocation -> {
                            CorrelationData correlation = invocation.getArgument(3);
                            correlation.setReturned(
                                    new org.springframework.amqp.core.ReturnedMessage(
                                            invocation.getArgument(2), 312, "NO_ROUTE", "", "x"));
                            correlation
                                    .getFuture()
                                    .complete(new CorrelationData.Confirm(true, null));
                            return null;
                        })
                .when(template)
                .send(
                        any(String.class),
                        any(String.class),
                        any(Message.class),
                        any(CorrelationData.class));

        assertThatThrownBy(
                        () ->
                                recoverer.recover(
                                        failedMessage(null), new IllegalStateException("x")))
                .isInstanceOf(ImmediateRequeueAmqpException.class);
    }

    /** A failure that says a dependency is down (the seat lock, the gateway), not a bad message. */
    private static final class DependencyDown extends RuntimeException
            implements RetryUntilAvailable {
        DependencyDown() {
            super("dependency down");
        }
    }

    private static List<Map<String, ?>> chainUsedUp(String base) {
        return List.of(
                death(base + ".retry.5s", 1),
                death(base + ".retry.30s", 1),
                death(base + ".retry.5m", 1));
    }

    @Test
    @DisplayName(
            "a down dependency on a waiting queue stays at the 5m stage after the chain is used up")
    void dependencyDown_onAWaitingQueue_neverParks() {
        recoverer.recover(
                failedMessageOn(QUEUE, chainUsedUp("unihub.seat-hold-expiry")),
                new ListenerExecutionFailedException(
                        "listener failed", new DependencyDown(), failedMessage(null)));

        sentTo("unihub.seat-hold-expiry.retry.5m");
    }

    @Test
    @DisplayName("long past the chain, it is still the 5m stage, never the parking lot")
    void dependencyDown_manyAttemptsLater_stillWaits() {
        recoverer.recover(
                failedMessageOn(
                        QUEUE,
                        List.of(
                                death("unihub.seat-hold-expiry.retry.5s", 1),
                                death("unihub.seat-hold-expiry.retry.30s", 1),
                                death("unihub.seat-hold-expiry.retry.5m", 40))),
                new DependencyDown());

        sentTo("unihub.seat-hold-expiry.retry.5m");
    }

    @Test
    @DisplayName("a down dependency still walks the chain 5s, 30s, 5m before it settles at 5m")
    void dependencyDown_walksTheChainFirst() {
        recoverer.recover(failedMessageOn(QUEUE, null), new DependencyDown());

        sentTo("unihub.seat-hold-expiry.retry.5s");
    }

    @Test
    @DisplayName("the refund-execution queue waits for the gateway in the same way")
    void dependencyDown_onTheRefundQueue_neverParks() {
        recoverer.recover(
                failedMessageOn(
                        "unihub.payment-refund-execution.queue",
                        chainUsedUp("unihub.payment-refund-execution")),
                new DependencyDown());

        sentTo("unihub.payment-refund-execution.retry.5m");
    }

    @Test
    @DisplayName("the same exception on any other queue follows the three-attempt rule")
    void dependencyDown_onAnotherQueue_isParkedAfterThree() {
        // The reconciliation consumer also calls the gateway, and is not one that waits.
        recoverer.recover(
                failedMessageOn(
                        "unihub.reconciliation.queue", chainUsedUp("unihub.reconciliation")),
                new DependencyDown());

        sentTo("unihub.parking-lot");
    }

    @Test
    @DisplayName("any other failure on a waiting queue is parked after three attempts")
    void otherFailure_onAWaitingQueue_isParkedAfterThree() {
        recoverer.recover(
                failedMessageOn(QUEUE, chainUsedUp("unihub.seat-hold-expiry")),
                new IllegalStateException("not a down dependency"));

        sentTo("unihub.parking-lot");
    }

    @Test
    @DisplayName("a permanent failure on a waiting queue is still parked at once")
    void permanentFailure_onAWaitingQueue_isParkedAtOnce() {
        recoverer.recover(
                failedMessageOn(QUEUE, null), new NonRetryableMessageException("bad payload"));

        sentTo("unihub.parking-lot");
    }

    @Test
    @DisplayName("only the seat-hold-expiry and refund-execution queues wait for their dependency")
    void whichQueuesWait() {
        assertThat(Topology.CONSUMED_QUEUES)
                .filteredOn(Topology.ConsumedQueue::waitsForDependency)
                .extracting(Topology.ConsumedQueue::name)
                .containsExactlyInAnyOrder(
                        "unihub.seat-hold-expiry.queue", "unihub.payment-refund-execution.queue");
    }

    // ---------- the parking observers (checklist D25: unihub_payment_refund_parked_total)
    // ----------

    @Test
    @DisplayName("an observer is told, with the business queue, each time a message is parked")
    void parked_isAnnouncedToObservers() {
        var told = new java.util.ArrayList<String>();
        var observing =
                new RetryChainRecoverer(
                        template,
                        new MessagingProperties(Duration.ofSeconds(1), Topology.RETRY_DELAYS),
                        List.of(told::add));

        observing.recover(failedMessage(null), new NonRetryableMessageException("bad"));
        observing.recover(
                failedMessage(
                        List.of(
                                death("unihub.seat-hold-expiry.retry.5s", 1),
                                death("unihub.seat-hold-expiry.retry.30s", 1),
                                death("unihub.seat-hold-expiry.retry.5m", 1))),
                new IllegalStateException("exhausted"));

        assertThat(told).containsExactly(QUEUE, QUEUE);
    }

    @Test
    @DisplayName("a retry is not a park: observers hear nothing while the chain still has stages")
    void retry_isNotAnnounced() {
        var told = new java.util.ArrayList<String>();
        var observing =
                new RetryChainRecoverer(
                        template,
                        new MessagingProperties(Duration.ofSeconds(1), Topology.RETRY_DELAYS),
                        List.of(told::add));

        observing.recover(failedMessage(null), new IllegalStateException("boom"));

        assertThat(told).isEmpty();
    }

    @Test
    @DisplayName("an observer that throws neither fails the park nor hides it from the others")
    void failingObserver_doesNotBreakThePark() {
        var told = new java.util.ArrayList<String>();
        var observing =
                new RetryChainRecoverer(
                        template,
                        new MessagingProperties(Duration.ofSeconds(1), Topology.RETRY_DELAYS),
                        List.of(
                                queue -> {
                                    throw new IllegalStateException("metrics down");
                                },
                                told::add));

        observing.recover(failedMessage(null), new NonRetryableMessageException("bad"));

        sentTo("unihub.parking-lot");
        assertThat(told).containsExactly(QUEUE);
    }
}
