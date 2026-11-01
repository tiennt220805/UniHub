package com.unihub.workshop.brochure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.unihub.shared.infrastructure.Redeliveries;
import com.unihub.workshop.application.command.SummarizeBrochureCommand;
import com.unihub.workshop.application.port.in.SummarizeBrochureUseCase;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

/**
 * INV-11 for the summarization consumer (Phase 5 checklist 9.4, C32, M-40, M-50): the same message
 * delivered again and handled at the same time by two threads. Unlike every other consumer,
 * redelivery here is not free: reprocessing overwrites {@code ai_summary} safely (workshop.md §3),
 * but each delivery is one more call to a paid service. So the evidence is a count: <b>what the AI
 * stand-in was asked, and the one value that ends up stored</b>, not only the absence of an error.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BrochureRedeliveryIT extends BrochureITSupport {

    @Autowired private SummarizeBrochureUseCase summarize;

    @Test
    @DisplayName("C32: three deliveries of one message are three AI calls and one stored summary")
    void sequentialRedelivery_callsTheAiOncePerDelivery() throws Exception {
        var brochure = aConfirmedBrochure(A_PDF);
        aiSummarizes("the only summary");

        publishRequested(brochure);
        publishRequested(brochure);
        publishRequested(brochure);

        await().atMost(Duration.ofSeconds(20)).until(() -> aiCalls() == 3);
        await().atMost(Duration.ofSeconds(10)).until(() -> queueDepth(QUEUE) == 0);
        Thread.sleep(700);
        assertThat(aiCalls()).as("one call per delivery, no more").isEqualTo(3);
        assertThat(summaryOf(brochure.workshop())).isEqualTo("the only summary");
        assertThat(objectExists(brochure.key())).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM workshops WHERE id = ?",
                                Integer.class,
                                brochure.workshop()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName(
            "C32: the same request handled by two threads at once ends with one stored summary")
    void sameRequest_fromTwoThreads_endsWithOneSummary() {
        var brochure = aConfirmedBrochure(A_PDF);
        aiSummarizes("one value");
        var command = new SummarizeBrochureCommand(brochure.workshop(), brochure.key());

        // nothing is allowed to fail: every step of a losing call is a plain read or a plain write
        Redeliveries.concurrently(2, () -> summarize.summarize(command)).assertOnlyFailuresOf();

        assertThat(aiCalls()).as("each of the two calls asked once").isEqualTo(2);
        assertThat(summaryOf(brochure.workshop())).isEqualTo("one value");
        assertThat(objectExists(brochure.key())).isTrue();
    }

    @Test
    @DisplayName("a rejected brochure delivered again finds the object gone and costs no AI call")
    void redeliveryAfterARejection_costsNothing() throws Exception {
        var brochure = aConfirmedBrochure("not a pdf".getBytes());
        aiSummarizes("must not be used");

        publishRequested(brochure);
        await().atMost(Duration.ofSeconds(15)).until(() -> !objectExists(brochure.key()));
        publishRequested(brochure);
        publishRequested(brochure);

        await().atMost(Duration.ofSeconds(10)).until(() -> queueDepth(QUEUE) == 0);
        Thread.sleep(700);
        assertThat(aiCalls()).isZero();
        assertThat(summaryOf(brochure.workshop())).isNull();
    }
}
