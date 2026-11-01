package com.unihub.workshop.infrastructure.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unihub.shared.messaging.NonRetryableMessageException;
import com.unihub.workshop.application.command.SummarizeBrochureCommand;
import com.unihub.workshop.infrastructure.in.messaging.SummarizationRequestedListener.SummarizationRequestedMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the listener accepts, and what it refuses for good instead of retrying (messaging.md §4).
 */
class SummarizationRequestedListenerTest {
    private static final UUID WORKSHOP = UUID.randomUUID();
    private final List<SummarizeBrochureCommand> received = new ArrayList<>();
    private final SummarizationRequestedListener listener =
            new SummarizationRequestedListener(received::add);

    @Test
    @DisplayName("a version 1 message becomes a command for that workshop and brochure")
    void maps() {
        listener.onSummarizationRequested(
                new SummarizationRequestedMessage(WORKSHOP, WORKSHOP + "/a.pdf", 1));

        assertThat(received)
                .containsExactly(new SummarizeBrochureCommand(WORKSHOP, WORKSHOP + "/a.pdf"));
    }

    @Test
    @DisplayName("a schema version this build does not know is refused for good, not retried")
    void unknownVersion() {
        for (Integer version : new Integer[] {null, 0, 2, -1}) {
            assertThatThrownBy(
                            () ->
                                    listener.onSummarizationRequested(
                                            new SummarizationRequestedMessage(
                                                    WORKSHOP, WORKSHOP + "/a.pdf", version)))
                    .isInstanceOf(NonRetryableMessageException.class);
        }
        assertThat(received).isEmpty();
    }

    @Test
    @DisplayName("a message without a workshop or a usable key is refused for good")
    void missingFields() {
        for (var message :
                List.of(
                        new SummarizationRequestedMessage(null, "k", 1),
                        new SummarizationRequestedMessage(WORKSHOP, null, 1),
                        new SummarizationRequestedMessage(WORKSHOP, "", 1),
                        new SummarizationRequestedMessage(WORKSHOP, "   ", 1))) {
            assertThatThrownBy(() -> listener.onSummarizationRequested(message))
                    .isInstanceOf(NonRetryableMessageException.class);
        }
        assertThat(received).isEmpty();
    }
}
