package com.unihub.workshop.cover;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aPublishedWorkshop;
import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.shared.security.TestJwts;
import com.unihub.workshop.brochure.BrochureHttpSupport;
import com.unihub.workshop.domain.model.WorkshopStatus;
import com.unihub.workshop.web.dto.Workshop;
import com.unihub.workshop.web.dto.WorkshopListResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;

/**
 * The two HTTP steps of a cover image upload over real HTTP against the real MinIO and database
 * (Phase 8 task 3.4, M-84; workshop.md §7, D120-D122). No relay and no consumer is involved: a
 * cover has nothing to compute afterwards, so a confirmation writes no outbox row.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CoverImageUploadIT extends BrochureHttpSupport {
    private static final HttpClient BROWSER = HttpClient.newHttpClient();

    private static final byte[] A_PNG = {
        (byte) 0x89,
        'P',
        'N',
        'G',
        0x0D,
        0x0A,
        0x1A,
        0x0A,
        0,
        0,
        0,
        13,
        'I',
        'H',
        'D',
        'R',
        0,
        0,
        0,
        1
    };
    private static final byte[] A_JPEG = {
        (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1, 1, 0
    };

    private ResponseEntityHolder issueCover(UUID workshop, String type, long length) {
        var response =
                post(
                        "/api/v1/workshops/" + workshop + "/cover-image-upload-url",
                        organizer(),
                        "{\"contentType\":\"" + type + "\",\"contentLength\":" + length + "}");
        return new ResponseEntityHolder(response);
    }

    private org.springframework.http.ResponseEntity<String> confirmCover(UUID workshop) {
        return post(
                "/api/v1/workshops/" + workshop + "/cover-image-upload-confirm", organizer(), null);
    }

    private record ResponseEntityHolder(org.springframework.http.ResponseEntity<String> response) {
        int status() {
            return response.getStatusCode().value();
        }

        URI url() {
            return URI.create(json(response).get("uploadUrl").asText());
        }
    }

    /** What the browser does with the issued URL. */
    private static int put(URI url, String contentType, byte[] body) throws Exception {
        var request =
                HttpRequest.newBuilder(url)
                        .header("Content-Type", contentType)
                        .PUT(BodyPublishers.ofByteArray(body))
                        .build();
        return BROWSER.send(request, BodyHandlers.discarding()).statusCode();
    }

    private String pendingCover(UUID workshop) {
        return jdbc.queryForObject(
                "SELECT pending_cover_image_key FROM workshops WHERE id = ?",
                String.class,
                workshop);
    }

    private String coverKey(UUID workshop) {
        return jdbc.queryForObject(
                "SELECT cover_image_key FROM workshops WHERE id = ?", String.class, workshop);
    }

    private Workshop detail(UUID workshop) {
        return http.getForObject("/api/v1/workshops/" + workshop, Workshop.class);
    }

    @Test
    @DisplayName(
            "issue, upload straight to the bucket, confirm: the cover is the workshop's, at once, everywhere")
    void issueUploadConfirm() throws Exception {
        var workshop = aWorkshop();
        assertThat(detail(workshop).coverImageUrl())
                .as("the cache now holds the workshop without a cover")
                .isNull();
        var versionBefore = versionOf(workshop);

        var issued = issueCover(workshop, "image/png", A_PNG.length);
        assertThat(issued.status()).isEqualTo(200);
        var pending = pendingCover(workshop);
        assertThat(pending).matches("covers/" + workshop + "/[0-9a-f-]{36}\\.png");
        assertThat(issued.url().toString()).contains(pending).contains("X-Amz-Expires=600");
        assertThat(coverKey(workshop)).as("issuing does not set the cover").isNull();
        assertThat(put(issued.url(), "image/png", A_PNG)).isEqualTo(200);

        var confirmed = confirmCover(workshop);

        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(confirmed).get("coverImageUrl").asText()).isEqualTo("/media/" + pending);
        assertThat(coverKey(workshop)).isEqualTo(pending);
        assertThat(pendingCover(workshop)).isNull();
        assertThat(versionOf(workshop))
                .as("neither step moves the version a seat claim checks")
                .isEqualTo(versionBefore);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM event_publications WHERE aggregate_id = ?",
                                Integer.class,
                                workshop))
                .as("nothing to compute afterwards, so nothing is published")
                .isZero();
        assertThat(detail(workshop).coverImageUrl())
                .as("the cached detail was dropped: the cover shows at once")
                .isEqualTo("/media/" + pending);
        var list = http.getForObject("/api/v1/workshops?size=100", WorkshopListResponse.class);
        assertThat(
                        list.items().stream()
                                .filter(i -> i.id().equals(workshop))
                                .findFirst()
                                .orElseThrow()
                                .coverImageUrl())
                .as("the catalog card carries the same path")
                .isEqualTo("/media/" + pending);
    }

    @Test
    @DisplayName("an organizer's edit does not erase the cover (the JPA entity never writes it)")
    void editKeepsTheCover() throws Exception {
        var workshop = aWorkshop();
        var issued = issueCover(workshop, "image/jpeg", A_JPEG.length);
        put(issued.url(), "image/jpeg", A_JPEG);
        confirmCover(workshop);
        var cover = coverKey(workshop);

        var patch =
                http.exchange(
                        "/api/v1/workshops/" + workshop,
                        org.springframework.http.HttpMethod.PATCH,
                        new org.springframework.http.HttpEntity<>(
                                "{\"title\":\"Renamed\"}",
                                jsonHeaders(TestJwts.valid(ORGANIZER, "WORKSHOP_EDIT"))),
                        String.class);

        assertThat(patch.getStatusCode().is2xxSuccessful()).as(patch.getBody()).isTrue();
        assertThat(coverKey(workshop)).isEqualTo(cover);
    }

    private static org.springframework.http.HttpHeaders jsonHeaders(String token) {
        var headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return headers;
    }

    @Test
    @DisplayName(
            "the URL is signed for the declared type and the exact size: any other PUT is refused")
    void urlIsSignedForTheDeclaredFile() throws Exception {
        var workshop = aWorkshop();
        var issued = issueCover(workshop, "image/png", A_PNG.length);

        var otherType = put(issued.url(), "image/jpeg", A_PNG);
        var otherSize =
                put(issued.url(), "image/png", java.util.Arrays.copyOf(A_PNG, A_PNG.length + 5));

        assertThat(otherType).as("a different Content-Type").isEqualTo(403);
        assertThat(otherSize).as("a different Content-Length").isEqualTo(403);
        assertThat(confirmCover(workshop).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName(
            "an SVG, a GIF, a PDF, a missing or a too-large declaration is a 400 and issues nothing")
    void refusesWhatIsNotAnAcceptableDeclaration() {
        var workshop = aWorkshop();

        assertThat(issueCover(workshop, "image/svg+xml", 100).status()).isEqualTo(400);
        assertThat(issueCover(workshop, "image/gif", 100).status()).isEqualTo(400);
        assertThat(issueCover(workshop, "application/pdf", 100).status()).isEqualTo(400);
        assertThat(issueCover(workshop, "image/png", 0).status()).isEqualTo(400);
        assertThat(issueCover(workshop, "image/png", 2_097_153).status()).isEqualTo(400);
        assertThat(
                        post(
                                        "/api/v1/workshops/" + workshop + "/cover-image-upload-url",
                                        organizer(),
                                        "{\"contentType\":\"image/png\"}")
                                .getStatusCode()
                                .value())
                .isEqualTo(400);
        assertThat(pendingCover(workshop)).isNull();
    }

    @Test
    @DisplayName(
            "a file that is not the declared image is deleted, its key forgotten, the cover untouched (422)")
    void rejectsAFileThatIsNotTheDeclaredImage() throws Exception {
        var workshop = aWorkshop();
        var svg =
                "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
                        .getBytes();
        var issued = issueCover(workshop, "image/png", svg.length);
        var pending = pendingCover(workshop);
        assertThat(put(issued.url(), "image/png", svg)).isEqualTo(200);
        assertThat(objectExists(pending)).isTrue();

        var confirmed = confirmCover(workshop);

        assertThat(confirmed.getStatusCode().value()).isEqualTo(422);
        assertThat(json(confirmed).get("type").asText())
                .isEqualTo("urn:unihub:problem:cover-image-rejected");
        assertThat(objectExists(pending)).as("a rejected file is not left to be served").isFalse();
        assertThat(pendingCover(workshop)).isNull();
        assertThat(coverKey(workshop)).isNull();
        assertThat(confirmCover(workshop).getStatusCode())
                .as("the key is gone: a new URL is needed")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName(
            "confirming before the browser has uploaded is a 404 that keeps the key; the retry works")
    void confirmBeforeUpload_isNotFoundAndRetryable() throws Exception {
        var workshop = aWorkshop();
        var issued = issueCover(workshop, "image/jpeg", A_JPEG.length);
        var pending = pendingCover(workshop);

        assertThat(confirmCover(workshop).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(pendingCover(workshop)).isEqualTo(pending);

        assertThat(put(issued.url(), "image/jpeg", A_JPEG)).isEqualTo(200);
        assertThat(confirmCover(workshop).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(coverKey(workshop)).isEqualTo(pending);
    }

    @Test
    @DisplayName("nothing issued, or confirmed twice, is a 409 with its own problem type")
    void confirmWithNothingToConfirm() throws Exception {
        var workshop = aWorkshop();

        var never = confirmCover(workshop);
        assertThat(never.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(never).get("type").asText())
                .isEqualTo("urn:unihub:problem:cover-image-upload-not-issued");

        var issued = issueCover(workshop, "image/png", A_PNG.length);
        put(issued.url(), "image/png", A_PNG);
        assertThat(confirmCover(workshop).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(confirmCover(workshop).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName(
            "a second cover replaces the first; the first object is an orphan, not a dangling reference")
    void replacingACover() throws Exception {
        var workshop = aWorkshop();
        var first = issueCover(workshop, "image/png", A_PNG.length);
        put(first.url(), "image/png", A_PNG);
        confirmCover(workshop);
        var firstKey = coverKey(workshop);

        var second = issueCover(workshop, "image/jpeg", A_JPEG.length);
        assertThat(coverKey(workshop))
                .as("issuing leaves the confirmed cover alone")
                .isEqualTo(firstKey);
        put(second.url(), "image/jpeg", A_JPEG);
        confirmCover(workshop);

        assertThat(coverKey(workshop)).isNotEqualTo(firstKey).endsWith(".jpg");
        assertThat(detail(workshop).coverImageUrl()).isEqualTo("/media/" + coverKey(workshop));
    }

    @Test
    @DisplayName("a cancelled workshop takes no cover (409), an unknown one is a 404")
    void workshopState() {
        var cancelled = aPublishedWorkshop().withStatus(WorkshopStatus.CANCELLED).build();
        tx.executeWithoutResult(s -> workshops.save(cancelled));
        try {
            assertThat(issueCover(cancelled.id().value(), "image/png", 10).status()).isEqualTo(409);
            assertThat(confirmCover(cancelled.id().value()).getStatusCode().value()).isEqualTo(409);
            assertThat(issueCover(UUID.randomUUID(), "image/png", 10).status()).isEqualTo(404);
            assertThat(confirmCover(UUID.randomUUID()).getStatusCode().value()).isEqualTo(404);
        } finally {
            jdbc.update("DELETE FROM workshops WHERE id = ?", cancelled.id().value());
        }
    }

    @Test
    @DisplayName("401 without a token and 403 without either workshop permission, on both steps")
    void permissions() {
        var workshop = aWorkshop();
        var urlPath = "/api/v1/workshops/" + workshop + "/cover-image-upload-url";
        var confirmPath = "/api/v1/workshops/" + workshop + "/cover-image-upload-confirm";
        var body = "{\"contentType\":\"image/png\",\"contentLength\":10}";
        var student = TestJwts.valid(UUID.randomUUID(), "REGISTRATION_CREATE");

        assertThat(post(urlPath, null, body).getStatusCode().value()).isEqualTo(401);
        assertThat(post(confirmPath, null, null).getStatusCode().value()).isEqualTo(401);
        assertThat(post(urlPath, student, body).getStatusCode().value()).isEqualTo(403);
        assertThat(post(confirmPath, student, null).getStatusCode().value()).isEqualTo(403);
        assertThat(
                        post(urlPath, TestJwts.valid(UUID.randomUUID(), "WORKSHOP_CREATE"), body)
                                .getStatusCode()
                                .value())
                .isEqualTo(200);
    }
}
