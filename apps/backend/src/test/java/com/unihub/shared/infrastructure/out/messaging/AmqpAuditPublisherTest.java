package com.unihub.shared.infrastructure.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.unihub.shared.infrastructure.out.messaging.AuditPublishException.Reason;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpTimeoutException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * What {@link AmqpAuditPublisher} says about each way a send can end, because that is what the
 * failure counter's {@code reason} is made of (Phase 6 checklist D68; observability.md §4): a nack
 * is the broker refusing (including a full {@code unihub.audit.queue}), a timeout is a broker that
 * said nothing, an error is everything else.
 */
class AmqpAuditPublisherTest {

    private final RabbitTemplate template = mock(RabbitTemplate.class);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AmqpAuditPublisher publisher =
            new AmqpAuditPublisher(template, Duration.ofMillis(100), () -> closed.set(true));

    private void brokerDoes(Consumer<CorrelationData> reply) {
        doAnswer(
                        invocation -> {
                            reply.accept(invocation.getArgument(3));
                            return null;
                        })
                .when(template)
                .send(
                        any(String.class),
                        any(String.class),
                        any(Message.class),
                        any(CorrelationData.class));
    }

    private void publish() {
        publisher.publish("11111111-1111-1111-1111-111111111111", "{}".getBytes(), null);
    }

    private Reason reasonOfFailure() {
        var failure = new AtomicReference<AuditPublishException>();
        assertThatThrownBy(this::publish)
                .isInstanceOfSatisfying(AuditPublishException.class, failure::set);
        return failure.get().reason();
    }

    @Test
    @DisplayName("an acked, routed message is a success")
    void ackedAndRouted() {
        brokerDoes(c -> c.getFuture().complete(new CorrelationData.Confirm(true, null)));

        assertThatCode(this::publish).doesNotThrowAnyException();
    }

    @Test
    @DisplayName(
            "the message goes to the shared exchange under the audit routing key, persistent,"
                    + " with its message-id, type and correlation id")
    void theMessageItself() {
        var sent = new AtomicReference<Message>();
        doAnswer(
                        invocation -> {
                            sent.set(invocation.getArgument(2));
                            ((CorrelationData) invocation.getArgument(3))
                                    .getFuture()
                                    .complete(new CorrelationData.Confirm(true, null));
                            return null;
                        })
                .when(template)
                .send(
                        org.mockito.ArgumentMatchers.eq("unihub.events"),
                        org.mockito.ArgumentMatchers.eq("unihub.system.audit.created"),
                        any(Message.class),
                        any(CorrelationData.class));

        publisher.publish("22222222-2222-2222-2222-222222222222", "{}".getBytes(), "abc123");

        var properties = sent.get().getMessageProperties();
        assertThat(properties.getMessageId()).isEqualTo("22222222-2222-2222-2222-222222222222");
        assertThat(properties.getType()).isEqualTo("unihub.system.audit.created");
        assertThat(properties.getContentType()).isEqualTo("application/json");
        assertThat(properties.getDeliveryMode().name()).isEqualTo("PERSISTENT");
        assertThat((String) properties.getHeader("correlationId")).isEqualTo("abc123");
    }

    @Test
    @DisplayName("no correlation id, no header")
    void noCorrelationIdNoHeader() {
        var sent = new AtomicReference<Message>();
        doAnswer(
                        invocation -> {
                            sent.set(invocation.getArgument(2));
                            ((CorrelationData) invocation.getArgument(3))
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

        publish();

        assertThat(sent.get().getMessageProperties().getHeaders())
                .doesNotContainKey("correlationId");
    }

    @Test
    @DisplayName("a nack is a nack")
    void nack() {
        brokerDoes(c -> c.getFuture().complete(new CorrelationData.Confirm(false, "queue full")));

        assertThat(reasonOfFailure()).isEqualTo(Reason.NACK);
    }

    @Test
    @DisplayName("no confirm within the timeout is a timeout")
    void noConfirm() {
        brokerDoes(c -> {});

        assertThat(reasonOfFailure()).isEqualTo(Reason.TIMEOUT);
    }

    @Test
    @DisplayName("a channel that cannot be opened in time is a timeout too: the same silent broker")
    void channelRpcTimeout() {
        doThrow(new AmqpTimeoutException("rpc"))
                .when(template)
                .send(
                        any(String.class),
                        any(String.class),
                        any(Message.class),
                        any(CorrelationData.class));

        assertThat(reasonOfFailure()).isEqualTo(Reason.TIMEOUT);
    }

    @Test
    @DisplayName("a refused connection is an error")
    void connectionRefused() {
        doThrow(new AmqpConnectException(new java.net.ConnectException("refused")))
                .when(template)
                .send(
                        any(String.class),
                        any(String.class),
                        any(Message.class),
                        any(CorrelationData.class));

        assertThat(reasonOfFailure()).isEqualTo(Reason.ERROR);
    }

    @Test
    @DisplayName("an acked but unroutable message is an error: the broker has nowhere to put it")
    void unroutable() {
        brokerDoes(
                c -> {
                    c.setReturned(
                            new ReturnedMessage(
                                    mock(Message.class), 312, "NO_ROUTE", "unihub.events", "k"));
                    c.getFuture().complete(new CorrelationData.Confirm(true, null));
                });

        assertThat(reasonOfFailure()).isEqualTo(Reason.ERROR);
    }

    @Test
    @DisplayName("destroying the publisher closes its own connection")
    void closesItsConnection() {
        publisher.destroy();

        assertThat(closed).isTrue();
    }
}
