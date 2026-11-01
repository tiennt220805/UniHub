package com.unihub.workshop.brochure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.unihub.shared.application.port.out.EventPublisher;
import com.unihub.workshop.application.event.BrochureSummarizationRequested;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * M-41, AC42.2, for {@code unihub.workshop.summarization.requested} — the ninth message type, run
 * with the relay on: the request is committed as an outbox row (what the confirmation does in its
 * own transaction), the real relay publishes it to the real exchange, the binding takes it to
 * {@code unihub.summarization.queue}, the real consumer reads the object from MinIO, asks the AI
 * stand-in and stores the summary, and no row is left {@code PENDING}. A message published to a key
 * no queue is bound to would be acknowledged and dropped; this is what shows that one is not.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BrochureRelayEndToEndIT extends BrochureITSupport {

    @DynamicPropertySource
    static void relayProperties(DynamicPropertyRegistry registry) {
        registry.add("unihub.jobs.outbox-relay.enabled", () -> "true");
        registry.add("unihub.jobs.outbox-relay.interval", () -> "PT0.2S");
    }

    @Autowired private EventPublisher events;

    @BeforeEach
    void noRowsFromEarlierTests() {
        // the live relay would publish whatever an earlier class left committed
        jdbc.update("DELETE FROM event_publications");
    }

    @Test
    @DisplayName(
            "AC42.2: a summarization request committed as an outbox row reaches the consumer through"
                    + " the real relay")
    void summarizationRequest_reachesTheConsumerThroughTheRealRelay() {
        var brochure = aConfirmedBrochure(A_PDF);
        aiSummarizes("through the relay");

        tx.executeWithoutResult(
                s ->
                        events.publish(
                                new BrochureSummarizationRequested(
                                        brochure.workshop(), brochure.key(), Instant.now())));

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () ->
                                assertThat(summaryOf(brochure.workshop()))
                                        .isEqualTo("through the relay"));
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () ->
                                assertThat(
                                                jdbc.queryForObject(
                                                        "SELECT count(*) FROM event_publications"
                                                                + " WHERE status = 'PENDING'",
                                                        Integer.class))
                                        .isZero());
        assertThat(aiCalls()).isEqualTo(1);
    }
}
