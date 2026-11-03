package com.unihub.shared.infrastructure.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicReference;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

/**
 * The listener side of the correlation chain (observability.md §1, Phase 6 checklist D66 (iii)):
 * the header becomes the MDC for one delivery; with no header the consumer's own trace id does; and
 * the MDC never outlives the delivery.
 */
class CorrelationIdListenerAdviceTest {

    @AfterEach
    void clean() {
        MDC.clear();
    }

    private static Message withHeader(String correlationId) {
        var properties = new MessageProperties();
        properties.setMessageId("m-1");
        if (correlationId != null) {
            properties.setHeader("correlationId", correlationId);
        }
        return new Message("{}".getBytes(), properties);
    }

    private static String seenDuring(CorrelationIdListenerAdvice advice, Message message)
            throws Throwable {
        var seen = new AtomicReference<String>();
        var invocation = mock(MethodInvocation.class);
        when(invocation.getArguments()).thenReturn(new Object[] {message});
        when(invocation.proceed())
                .thenAnswer(
                        call -> {
                            seen.set(MDC.get("correlationId"));
                            return null;
                        });
        advice.invoke(invocation);
        return seen.get();
    }

    @Test
    @DisplayName("the header is the correlation id of the delivery")
    void headerWins() throws Throwable {
        var advice = new CorrelationIdListenerAdvice(() -> "own-trace-id");

        assertThat(seenDuring(advice, withHeader("from-the-request")))
                .isEqualTo("from-the-request");
        assertThat(MDC.get("correlationId")).as("cleared afterwards").isNull();
    }

    @Test
    @DisplayName("a message without the header is handled under the consumer's own trace id")
    void ownTraceIdWithoutHeader() throws Throwable {
        var advice = new CorrelationIdListenerAdvice(() -> "own-trace-id");

        assertThat(seenDuring(advice, withHeader(null))).isEqualTo("own-trace-id");
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    @DisplayName("no header and no trace: no id, and a stale one from the thread is not reused")
    void nothingToUse() throws Throwable {
        var advice = new CorrelationIdListenerAdvice();

        assertThat(seenDuring(advice, withHeader(null))).isNull();
    }

    @Test
    @DisplayName("two deliveries in a row on one thread do not share an id")
    void noLeakToTheNextMessage() throws Throwable {
        var advice = new CorrelationIdListenerAdvice();

        seenDuring(advice, withHeader("first"));

        assertThat(seenDuring(advice, withHeader("second"))).isEqualTo("second");
        assertThat(seenDuring(advice, withHeader(null))).isNull();
    }
}
