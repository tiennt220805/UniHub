package com.unihub.workshop.brochure;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.application.port.out.SeatCounter;
import com.unihub.workshop.web.dto.Workshop;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.annotation.DirtiesContext;

/**
 * The summarization consumer, through the broker, the real MinIO and the real database, with a
 * WireMock stand-in for the AI service (Phase 5 checklist 9.3, M-50, D60; workshop.md §3, §8, §9).
 * The message is put on the exchange by the test, as the relay would; the confirmation step that
 * writes it is {@code BrochureConfirmIT}.
 */
// Its AI stand-in belongs to this class, and its listener must not outlive it.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SummarizationConsumerIT extends BrochureITSupport {

    @Autowired private TestRestTemplate http;
    @Autowired private SeatCounter seats;

    private Workshop detail(UUID workshop) {
        return http.getForObject("/api/v1/workshops/" + workshop, Workshop.class);
    }

    private void settle() throws InterruptedException {
        await().atMost(Duration.ofSeconds(10)).until(() -> queueDepth(QUEUE) == 0);
        // the last delivery may still be in the listener
        Thread.sleep(700);
    }

    @Test
    @DisplayName("M-50: a PDF in the bucket reaches the AI service whole and its summary is stored")
    void pdf_isSummarized() {
        var brochure = aConfirmedBrochure(A_PDF);
        aiSummarizes("Buổi workshop về khóa phân tán.");

        publishRequested(brochure);

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () ->
                                assertThat(summaryOf(brochure.workshop()))
                                        .isEqualTo("Buổi workshop về khóa phân tán."));
        ai.verify(
                postRequestedFor(urlEqualTo(SUMMARIZE))
                        .withHeader("Authorization", equalTo("Bearer test-key-not-a-secret"))
                        .withRequestBody(containing("%PDF-1.7"))
                        .withRequestBody(containing("%%EOF")));
        assertThat(objectExists(brochure.key())).as("a good brochure stays in the bucket").isTrue();
    }

    @Test
    @DisplayName("workshop.md §9: a summary of any length is stored as at most 500 characters")
    void longSummary_isCut() {
        var brochure = aConfirmedBrochure(A_PDF);
        aiSummarizes("x".repeat(2_000));

        publishRequested(brochure);

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(summaryOf(brochure.workshop())).hasSize(500));
    }

    @Test
    @DisplayName("§8: an object that is not a PDF is deleted and the AI service is never called")
    void notAPdf_isDeleted() throws Exception {
        var brochure = aConfirmedBrochure("MZ not a pdf, only named like one".getBytes());
        aiSummarizes("must not be used");

        publishRequested(brochure);

        await().atMost(Duration.ofSeconds(15)).until(() -> !objectExists(brochure.key()));
        settle();
        assertThat(aiCalls()).isZero();
        assertThat(summaryOf(brochure.workshop())).isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT brochure_s3_key FROM workshops WHERE id = ?",
                                String.class,
                                brochure.workshop()))
                .as("the key stays on the row, pointing at where the file was (§8)")
                .isEqualTo(brochure.key());
    }

    @Test
    @DisplayName("§6, §8: a file over 10 MB is deleted and the AI service is never called")
    void tooLarge_isDeleted() throws Exception {
        var big = new byte[10 * 1024 * 1024 + 1];
        System.arraycopy("%PDF-1.7\n".getBytes(), 0, big, 0, 9);
        var brochure = aConfirmedBrochure(big);
        aiSummarizes("must not be used");

        publishRequested(brochure);

        await().atMost(Duration.ofSeconds(30)).until(() -> !objectExists(brochure.key()));
        settle();
        assertThat(aiCalls()).isZero();
        assertThat(summaryOf(brochure.workshop())).isNull();
    }

    @Test
    @DisplayName("§9, AC60.2: a 422 is acknowledged: no retry, no summary, nothing parked")
    void unprocessable_isAcknowledged() throws Exception {
        var brochure = aConfirmedBrochure(A_PDF);
        aiAnswers(422, "{\"reason\":\"UNSUPPORTED_CONTENT\"}");
        var parkedBefore = queueDepth(PARKING_LOT);

        publishRequested(brochure);

        await().atMost(Duration.ofSeconds(15)).until(() -> aiCalls() == 1);
        // a retry would come within 0.3 s per stage; wait several stages
        settle();
        Thread.sleep(1_500);
        assertThat(aiCalls()).as("never retried").isEqualTo(1);
        assertThat(summaryOf(brochure.workshop())).isNull();
        assertThat(queueDepth(PARKING_LOT)).isEqualTo(parkedBefore);
        assertThat(objectExists(brochure.key())).isTrue();
    }

    @Test
    @DisplayName(
            "§9, §10: an AI service that keeps failing is called 1 + 3 times, then the message is parked")
    void aiDown_isRetriedThenParked() {
        var brochure = aConfirmedBrochure(A_PDF);
        aiAnswers(503, "{}");
        var parkedBefore = queueDepth(PARKING_LOT);

        publishRequested(brochure);

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () -> assertThat(queueDepth(PARKING_LOT)).isEqualTo(parkedBefore + 1));
        assertThat(aiCalls()).isEqualTo(4);
        assertThat(summaryOf(brochure.workshop())).isNull();
    }

    @Test
    @DisplayName("§10: a service that fails once and then answers gives the summary on the retry")
    void aiRecovers_summaryArrives() {
        var brochure = aConfirmedBrochure(A_PDF);
        ai.stubFor(
                post(urlEqualTo(SUMMARIZE))
                        .inScenario("flaky")
                        .whenScenarioStateIs(Scenario.STARTED)
                        .willReturn(aResponse().withStatus(503))
                        .willSetStateTo("up"));
        ai.stubFor(
                post(urlEqualTo(SUMMARIZE))
                        .inScenario("flaky")
                        .whenScenarioStateIs("up")
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("{\"summary\":\"second try\"}")));

        publishRequested(brochure);

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () -> assertThat(summaryOf(brochure.workshop())).isEqualTo("second try"));
        assertThat(aiCalls()).isEqualTo(2);
    }

    @Test
    @DisplayName(
            "a request for a brochure that has been replaced since is finished without any AI call")
    void staleRequest_isSkipped() throws Exception {
        var workshop = aWorkshop();
        var oldKey = upload(workshop, A_PDF);
        var newKey = upload(workshop, A_PDF);
        jdbc.update("UPDATE workshops SET brochure_s3_key = ? WHERE id = ?", newKey, workshop);
        aiSummarizes("must not be used");

        publishRequested(new Brochure(workshop, oldKey));

        settle();
        assertThat(aiCalls()).isZero();
        assertThat(summaryOf(workshop)).isNull();
        assertThat(objectExists(oldKey)).isTrue();
    }

    @Test
    @DisplayName(
            "§2: the detail the student sees shows the summary at once, not after the 300 s TTL")
    void summary_isNotHiddenByTheCache() {
        var brochure = aConfirmedBrochure(A_PDF);
        aiSummarizes("fresh summary");
        var warmed = detail(brochure.workshop());
        assertThat(warmed.aiSummary())
                .as("the cache now holds the workshop without a summary")
                .isNull();
        assertThat(detail(brochure.workshop()).aiSummary()).isNull();

        publishRequested(brochure);

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () ->
                                assertThat(detail(brochure.workshop()).aiSummary())
                                        .isEqualTo("fresh summary"));
    }

    @Test
    @DisplayName(
            "a summary does not move the version: a student claiming a seat at that moment is not made to retry")
    void summary_doesNotBumpTheVersion() {
        var brochure = aConfirmedBrochure(A_PDF);
        aiSummarizes("no version change");
        var id = WorkshopId.of(brochure.workshop());
        var snapshot = seats.read(id).orElseThrow();
        var versionBefore = versionOf(brochure.workshop());

        publishRequested(brochure);
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(summaryOf(brochure.workshop())).isNotNull());

        assertThat(versionOf(brochure.workshop())).isEqualTo(versionBefore);
        boolean claimed =
                Boolean.TRUE.equals(tx.execute(s -> seats.tryClaim(id, snapshot.version())));
        assertThat(claimed)
                .as("a claim based on a read from before the summary still succeeds")
                .isTrue();
    }

    @Test
    @DisplayName(
            "an organizer's edit saved from a copy read earlier does not erase the key or the summary")
    void staleEdit_doesNotOverwriteTheBrochureColumns() {
        var brochure = aConfirmedBrochure(A_PDF);
        var id = WorkshopId.of(brochure.workshop());
        var loadedBefore = tx.execute(s -> workshops.findById(id).orElseThrow());
        aiSummarizes("kept");
        publishRequested(brochure);
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(summaryOf(brochure.workshop())).isEqualTo("kept"));

        loadedBefore.revise(
                "A new title",
                loadedBefore.description(),
                loadedBefore.speakerName(),
                loadedBefore.speakerBio(),
                loadedBefore.location(),
                loadedBefore.capacity(),
                loadedBefore.price());
        tx.executeWithoutResult(s -> workshops.save(loadedBefore));

        assertThat(summaryOf(brochure.workshop())).isEqualTo("kept");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT brochure_s3_key FROM workshops WHERE id = ?",
                                String.class,
                                brochure.workshop()))
                .isEqualTo(brochure.key());
    }
}
