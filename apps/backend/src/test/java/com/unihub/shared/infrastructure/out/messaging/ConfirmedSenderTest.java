package com.unihub.shared.infrastructure.out.messaging;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.unihub.shared.application.port.out.BrokerPublishException;
import com.unihub.shared.application.port.out.BrokerUnavailableException;
import java.time.Duration;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.AmqpTimeoutException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * What {@link ConfirmedSender} says about a failed send (Phase 4 checklist 7.4, AC42.5, D42). The
 * relay ends its cycle on {@link BrokerUnavailableException} and carries on to the next row on
 * {@link BrokerPublishException}, so the class of each failure is the whole behaviour: a broker
 * that does not answer is one failure for the cycle, a broker that refuses a message is one failure
 * for the row.
 */
class ConfirmedSenderTest {

    private final RabbitTemplate template = mock(RabbitTemplate.class);
    private final ConfirmedSender sender = new ConfirmedSender(template, Duration.ofMillis(100));
    private final Message message = new Message("{}".getBytes(), new MessageProperties());

    /** The broker's side of one send: what it does with the correlation data. */
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

    private void send() {
        sender.send("unihub.events", "unihub.test.key", message);
    }

    @Test
    @DisplayName("an acked and routed message is a success")
    void ackedAndRouted() {
        brokerDoes(c -> c.getFuture().complete(new CorrelationData.Confirm(true, null)));

        assertThatCode(this::send).doesNotThrowAnyException();
    }

    @Test
    @DisplayName(
            "AC42.5: no confirm in time means the broker is unavailable, not that this row failed")
    void confirmTimeout_isUnavailable() {
        brokerDoes(c -> {});

        assertThatThrownBy(this::send)
                .isInstanceOf(BrokerUnavailableException.class)
                .hasMessageContaining("no publisher confirm");
    }

    @Test
    @DisplayName("a refused connection means the broker is unavailable")
    void connectionRefused_isUnavailable() {
        doThrow(new AmqpConnectException(new java.net.ConnectException("refused")))
                .when(template)
                .send(
                        any(String.class),
                        any(String.class),
                        any(Message.class),
                        any(CorrelationData.class));

        assertThatThrownBy(this::send).isInstanceOf(BrokerUnavailableException.class);
    }

    @Test
    @DisplayName("a nack is a failure of this message only")
    void nack_isAPublishFailure() {
        brokerDoes(c -> c.getFuture().complete(new CorrelationData.Confirm(false, "disk full")));

        assertThatThrownBy(this::send)
                .isInstanceOf(BrokerPublishException.class)
                .hasMessageContaining("disk full");
    }

    @Test
    @DisplayName("an unroutable message, acked but returned, is a failure of this message only")
    void returned_isAPublishFailure() {
        brokerDoes(
                c -> {
                    c.setReturned(new ReturnedMessage(message, 312, "NO_ROUTE", "", "k"));
                    c.getFuture().complete(new CorrelationData.Confirm(true, null));
                });

        assertThatThrownBy(this::send)
                .isInstanceOf(BrokerPublishException.class)
                .hasMessageContaining("unroutable");
    }

    @Test
    @DisplayName("AC42.5: a channel that cannot be opened in time means the broker is unavailable")
    void channelOpenTimeout_isUnavailable() {
        // what the client throws when channel.open gets no reply, and how Spring wraps it
        doThrow(
                        new AmqpIOException(
                                new java.io.IOException(
                                        "channel.open",
                                        new com.rabbitmq.client.ChannelContinuationTimeoutException(
                                                new java.util.concurrent.TimeoutException(),
                                                null,
                                                1,
                                                null))))
                .when(template)
                .send(
                        any(String.class),
                        any(String.class),
                        any(Message.class),
                        any(CorrelationData.class));

        assertThatThrownBy(this::send).isInstanceOf(BrokerUnavailableException.class);
    }

    @Test
    @DisplayName("an AMQP timeout, as Spring translates it, means the broker is unavailable")
    void amqpTimeout_isUnavailable() {
        doThrow(new AmqpTimeoutException("no reply"))
                .when(template)
                .send(
                        any(String.class),
                        any(String.class),
                        any(Message.class),
                        any(CorrelationData.class));

        assertThatThrownBy(this::send).isInstanceOf(BrokerUnavailableException.class);
    }

    @Test
    @DisplayName("another failure of the send itself is a failure of this message only")
    void otherAmqpFailure_isAPublishFailure() {
        doThrow(new AmqpIOException(new java.io.IOException("channel closed")))
                .when(template)
                .send(
                        any(String.class),
                        any(String.class),
                        any(Message.class),
                        any(CorrelationData.class));

        assertThatThrownBy(this::send).isInstanceOf(BrokerPublishException.class);
    }
}
