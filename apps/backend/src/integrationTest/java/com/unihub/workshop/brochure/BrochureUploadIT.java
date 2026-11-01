package com.unihub.workshop.brochure;

import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.shared.infrastructure.Redeliveries;
import com.unihub.shared.security.TestJwts;
import com.unihub.workshop.web.dto.Workshop;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;

/**
 * The two HTTP steps of a brochure upload, over real HTTP against the real MinIO and database
 * (Phase 5 checklist 9.2, M-50, D60; workshop.md §7). The relay is off, so the outbox row a
 * confirmation writes stays {@code PENDING} and can be counted; the whole path to a summary is
 * {@code BrochureSummarizationIT}.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BrochureUploadIT extends BrochureHttpSupport {

    private Workshop detail(UUID workshop) {
        return http.getForObject("/api/v1/workshops/" + workshop, Workshop.class);
    }

    @Test
    @DisplayName(
            "§7: issue, upload straight to the bucket, confirm: the key is the workshop's and one request is written")
    void issueUploadConfirm() throws Exception {
        var workshop = aWorkshop();
        var warmed = detail(workshop);
        assertThat(warmed.hasBrochure())
                .as("the cache now holds the workshop without a brochure")
                .isFalse();
        var versionBefore = versionOf(workshop);

        var issued = issue(workshop, organizer());
        assertThat(issued.getStatusCode()).isEqualTo(HttpStatus.OK);
        var pending = pendingKeyOf(workshop);
        assertThat(pending).matches(workshop + "/[0-9a-f-]{36}\\.pdf");
        assertThat(uploadUrl(issued).toString()).contains(pending).contains("X-Amz-Expires=600");
        assertThat(brochureKeyOf(workshop)).as("issuing does not set the brochure").isNull();
        assertThat(put(uploadUrl(issued), A_PDF)).isEqualTo(200);

        var confirmed = confirm(workshop, organizer());

        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(confirmed).get("hasBrochure").asBoolean()).isTrue();
        assertThat(brochureKeyOf(workshop)).isEqualTo(pending);
        assertThat(pendingKeyOf(workshop)).isNull();
        assertThat(summarizationRequests(workshop)).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT payload->>'brochureKey' FROM event_publications"
                                        + " WHERE aggregate_id = ?",
                                String.class,
                                workshop))
                .isEqualTo(pending);
        assertThat(versionOf(workshop))
                .as("neither step moves the version a seat claim checks")
                .isEqualTo(versionBefore);
        assertThat(detail(workshop).hasBrochure())
                .as("the cached detail was dropped: has_brochure is true at once")
                .isTrue();
    }

    @Test
    @DisplayName(
            "§7: confirming before the browser has uploaded is a 404 that keeps the key; the retry works")
    void confirmBeforeUpload_isNotFoundAndRetryable() throws Exception {
        var workshop = aWorkshop();
        var issued = issue(workshop, organizer());
        var pending = pendingKeyOf(workshop);

        var early = confirm(workshop, organizer());

        assertThat(early.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(brochureKeyOf(workshop)).isNull();
        assertThat(pendingKeyOf(workshop))
                .as("kept, so the same flow can be finished")
                .isEqualTo(pending);
        assertThat(summarizationRequests(workshop)).isZero();

        assertThat(put(uploadUrl(issued), A_PDF)).isEqualTo(200);
        assertThat(confirm(workshop, organizer()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(brochureKeyOf(workshop)).isEqualTo(pending);
    }

    @Test
    @DisplayName(
            "§7: nothing issued, or already confirmed, is a 409 with its own problem type and no request")
    void nothingToConfirm_isConflict() throws Exception {
        var workshop = aWorkshop();

        var never = confirm(workshop, organizer());
        assertThat(never.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(never).get("type").asText())
                .isEqualTo("urn:unihub:problem:brochure-upload-not-issued");

        var issued = issue(workshop, organizer());
        put(uploadUrl(issued), A_PDF);
        assertThat(confirm(workshop, organizer()).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(confirm(workshop, organizer()).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(summarizationRequests(workshop)).as("one request, not two").isEqualTo(1);
    }

    @Test
    @DisplayName(
            "two confirmations at once for one issued key: one 200, one 409, exactly one summarization request")
    void concurrentConfirms_writeOneRequest() throws Exception {
        var workshop = aWorkshop();
        var issued = issue(workshop, organizer());
        put(uploadUrl(issued), A_PDF);
        var ok = new AtomicInteger();
        var conflict = new AtomicInteger();
        var token = organizer();

        Redeliveries.concurrently(
                        2,
                        () -> {
                            var status = confirm(workshop, token).getStatusCode();
                            if (status == HttpStatus.OK) {
                                ok.incrementAndGet();
                            } else if (status == HttpStatus.CONFLICT) {
                                conflict.incrementAndGet();
                            }
                        })
                .assertOnlyFailuresOf();

        assertThat(ok.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(1);
        assertThat(summarizationRequests(workshop)).as("one paid AI call, never two").isEqualTo(1);
    }

    @Test
    @DisplayName(
            "§5: asking for a new URL replaces the issued key; the old one can no longer be confirmed")
    void reissue_replacesThePendingKey() throws Exception {
        var workshop = aWorkshop();
        var first = issue(workshop, organizer());
        var firstKey = pendingKeyOf(workshop);
        var second = issue(workshop, organizer());
        var secondKey = pendingKeyOf(workshop);
        assertThat(secondKey).isNotEqualTo(firstKey);

        put(uploadUrl(first), A_PDF); // the browser used the old URL

        assertThat(confirm(workshop, organizer()).getStatusCode())
                .as("the issued key is the second one, and nothing is there")
                .isEqualTo(HttpStatus.NOT_FOUND);
        put(uploadUrl(second), A_PDF);
        assertThat(confirm(workshop, organizer()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(brochureKeyOf(workshop)).isEqualTo(secondKey);
        assertThat(objectExists(firstKey))
                .as("the replaced object is an orphan, as §5 accepts")
                .isTrue();
    }

    @Test
    @DisplayName("a second brochure replaces the first on the row, and asks for its own summary")
    void replacingABrochure() throws Exception {
        var workshop = aWorkshop();
        put(uploadUrl(issue(workshop, organizer())), A_PDF);
        confirm(workshop, organizer());
        var firstKey = brochureKeyOf(workshop);

        put(uploadUrl(issue(workshop, organizer())), A_PDF);
        assertThat(confirm(workshop, organizer()).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(brochureKeyOf(workshop)).isNotEqualTo(firstKey);
        assertThat(summarizationRequests(workshop)).isEqualTo(2);
    }

    @Test
    @DisplayName("either permission is enough; none is 403; no token is 401")
    void authorization() {
        var workshop = aWorkshop();
        var create = TestJwts.valid(UUID.randomUUID(), "WORKSHOP_CREATE");
        var edit = TestJwts.valid(UUID.randomUUID(), "WORKSHOP_EDIT");
        var student = TestJwts.valid(UUID.randomUUID(), "REGISTRATION_CREATE");

        assertThat(issue(workshop, create).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(issue(workshop, edit).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(issue(workshop, student).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(issue(workshop, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(confirm(workshop, student).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(confirm(workshop, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(pendingKeyOf(workshop)).as("a refused caller wrote nothing").isNotNull();
    }

    @Test
    @DisplayName("a content type other than application/pdf, or none, is a 400 and writes nothing")
    void invalidContentType() {
        var workshop = aWorkshop();

        for (var body :
                new String[] {
                    "{\"contentType\":\"text/html\"}", "{}", "{\"contentType\":null}", "not json"
                }) {
            var response =
                    post(
                            "/api/v1/workshops/" + workshop + "/brochure-upload-url",
                            organizer(),
                            body);
            assertThat(response.getStatusCode()).as(body).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(pendingKeyOf(workshop)).isNull();
    }

    @Test
    @DisplayName(
            "a workshop that does not exist is a 404; a cancelled one is a 409; a malformed id is a 400")
    void unknownOrTerminalWorkshop() {
        var missing = UUID.randomUUID();
        assertThat(issue(missing, organizer()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(confirm(missing, organizer()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        var workshop = aWorkshop();
        jdbc.update("UPDATE workshops SET status = 'CANCELLED' WHERE id = ?", workshop);
        assertThat(issue(workshop, organizer()).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(confirm(workshop, organizer()).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        var malformed =
                post(
                        "/api/v1/workshops/not-a-uuid/brochure-upload-url",
                        organizer(),
                        "{\"contentType\":\"application/pdf\"}");
        assertThat(malformed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("the live OpenAPI document describes both operations, as the contract does")
    void documented() {
        var docs = http.getForObject("/v3/api-docs", String.class);

        assertThat(docs)
                .contains("/api/v1/workshops/{id}/brochure-upload-url")
                .contains("/api/v1/workshops/{id}/brochure-upload-confirm")
                .contains("requestBrochureUploadUrl")
                .contains("confirmBrochureUpload");
    }
}
