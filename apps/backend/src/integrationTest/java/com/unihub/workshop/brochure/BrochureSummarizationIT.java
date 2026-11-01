package com.unihub.workshop.brochure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.unihub.workshop.web.dto.Workshop;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * M-50 end to end (Phase 5 checklist 9.5; workshop.md §3, §7, §8, §9; D45, D60): the Organizer's
 * upload through a presigned URL to the real MinIO, the confirmation over HTTP, the real relay, the
 * real consumer, and a WireMock stand-in for the AI service — until the student's {@code GET
 * /api/v1/workshops/{id}} shows the summary. The relay is on, so the outbox row is the only way the
 * request travels; nothing here puts a message on the broker by hand.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BrochureSummarizationIT extends BrochureHttpSupport {

    @DynamicPropertySource
    static void relayProperties(DynamicPropertyRegistry registry) {
        registry.add("unihub.jobs.outbox-relay.enabled", () -> "true");
        registry.add("unihub.jobs.outbox-relay.interval", () -> "PT0.2S");
    }

    @BeforeEach
    void noRowsFromEarlierTests() {
        // the live relay would publish whatever an earlier class left committed
        jdbc.update("DELETE FROM event_publications");
    }

    private Workshop detail(UUID workshop) {
        return http.getForObject("/api/v1/workshops/" + workshop, Workshop.class);
    }

    private void uploadAndConfirm(UUID workshop, byte[] content) throws Exception {
        var issued = issue(workshop, organizer());
        assertThat(issued.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(put(uploadUrl(issued), content)).isEqualTo(200);
        assertThat(confirm(workshop, organizer()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName(
            "M-50: upload, confirm, relay, consumer, AI stand-in: the student sees the summary")
    void uploadedPdf_ends_asASummaryOnTheWorkshopDetail() throws Exception {
        var workshop = aWorkshop();
        aiSummarizes("Buổi workshop giới thiệu khóa phân tán và lý do nó chỉ mang tính tư vấn.");
        var before = detail(workshop);
        assertThat(before.aiSummary()).isNull();
        assertThat(before.hasBrochure()).isFalse();

        uploadAndConfirm(workshop, A_PDF);

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () ->
                                assertThat(detail(workshop).aiSummary())
                                        .isEqualTo(
                                                "Buổi workshop giới thiệu khóa phân tán và lý do nó chỉ mang tính tư vấn."));
        assertThat(detail(workshop).hasBrochure()).isTrue();
        assertThat(aiCalls()).isEqualTo(1);
        assertThat(objectExists(brochureKeyOf(workshop))).isTrue();
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () ->
                                assertThat(
                                                jdbc.queryForObject(
                                                        "SELECT count(*) FROM event_publications"
                                                                + " WHERE status = 'PENDING'",
                                                        Integer.class))
                                        .isZero());
    }

    @Test
    @DisplayName(
            "§8: a file that is not a PDF is accepted by the upload, then removed; the AI service is never called")
    void wrongType_isRemoved_andNeverSummarized() throws Exception {
        var workshop = aWorkshop();
        aiSummarizes("must not be used");

        uploadAndConfirm(workshop, "MZ\u0090 an executable renamed brochure.pdf".getBytes());
        var key = brochureKeyOf(workshop);

        await().atMost(Duration.ofSeconds(30)).until(() -> !objectExists(key));
        assertThat(aiCalls()).isZero();
        assertThat(detail(workshop).aiSummary()).isNull();
        assertThat(detail(workshop).hasBrochure())
                .as("the row still says a brochure was confirmed (§8 leaves the key as it was)")
                .isTrue();
    }

    @Test
    @DisplayName(
            "§6, §8: a file over 10 MB is removed the same way, without a call to the AI service")
    void oversized_isRemoved() throws Exception {
        var workshop = aWorkshop();
        aiSummarizes("must not be used");
        var big = new byte[10 * 1024 * 1024 + 1];
        System.arraycopy("%PDF-1.7\n".getBytes(), 0, big, 0, 9);

        uploadAndConfirm(workshop, big);
        var key = brochureKeyOf(workshop);

        await().atMost(Duration.ofSeconds(60)).until(() -> !objectExists(key));
        assertThat(aiCalls()).isZero();
        assertThat(detail(workshop).aiSummary()).isNull();
    }

    @Test
    @DisplayName(
            "§9, AC60.2: a 422 from the AI service ends the flow quietly: called once, no summary, the file stays")
    void unprocessable_endsQuietly() throws Exception {
        var workshop = aWorkshop();
        aiAnswers(422, "{\"reason\":\"UNSUPPORTED_CONTENT\"}");

        uploadAndConfirm(workshop, A_PDF);

        await().atMost(Duration.ofSeconds(30)).until(() -> aiCalls() == 1);
        Thread.sleep(1_500); // several retry stages would fit in this wait
        assertThat(aiCalls()).as("not retried").isEqualTo(1);
        assertThat(detail(workshop).aiSummary()).isNull();
        assertThat(objectExists(brochureKeyOf(workshop))).isTrue();
    }

    @Test
    @DisplayName("replacing a brochure gets a second summary, which replaces the first")
    void replacement_getsItsOwnSummary() throws Exception {
        var workshop = aWorkshop();
        aiSummarizes("first");
        uploadAndConfirm(workshop, A_PDF);
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(detail(workshop).aiSummary()).isEqualTo("first"));
        var firstKey = brochureKeyOf(workshop);

        aiSummarizes("second");
        uploadAndConfirm(workshop, A_PDF);

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(detail(workshop).aiSummary()).isEqualTo("second"));
        assertThat(brochureKeyOf(workshop)).isNotEqualTo(firstKey);
        assertThat(aiCalls()).isEqualTo(2);
    }
}
