package com.unihub.workshop.infrastructure.out.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.workshop.application.port.out.BrochureStorage.ObjectNotFoundException;
import com.unihub.workshop.application.port.out.BrochureStorage.StorageUnavailableException;
import com.unihub.workshop.infrastructure.config.BrochureStorageConfig;
import com.unihub.workshop.infrastructure.config.ObjectStorageProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * The brochure bucket against the real MinIO (Phase 5 checklist 9.1, M-50, workshop.md §4-§7): the
 * presigned PUT, {@code HeadObject}, {@code GetObject} and {@code DeleteObject} the use cases rely
 * on, and what a bucket that cannot be reached looks like. The client and presigner are built by
 * the application's own configuration, not by the test.
 */
class S3BrochureStorageIT extends IntegrationTestSupport {
    private static final byte[] PDF = "%PDF-1.7\nhello\n%%EOF".getBytes(StandardCharsets.US_ASCII);
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static ObjectStorageProperties settings;
    private static S3Client client;
    private static S3Presigner presigner;
    private static S3BrochureStorage storage;

    @BeforeAll
    static void connect() {
        settings =
                new ObjectStorageProperties(
                        MINIO.getS3URL(),
                        null,
                        null,
                        MINIO.getUserName(),
                        MINIO.getPassword(),
                        true,
                        null,
                        null);
        var config = new BrochureStorageConfig();
        client = config.brochureS3Client(settings);
        presigner = config.brochureS3Presigner(settings);
        try {
            client.headBucket(b -> b.bucket(settings.bucket()));
        } catch (software.amazon.awssdk.services.s3.model.NoSuchBucketException e) {
            client.createBucket(b -> b.bucket(settings.bucket()));
        }
        storage = new S3BrochureStorage(client, presigner, settings);
    }

    @AfterAll
    static void close() {
        client.close();
        presigner.close();
    }

    private static int put(URI url, String contentType, byte[] body)
            throws IOException, InterruptedException {
        var request =
                HttpRequest.newBuilder(url)
                        .header("Content-Type", contentType)
                        .PUT(BodyPublishers.ofByteArray(body))
                        .build();
        return HTTP.send(request, BodyHandlers.discarding()).statusCode();
    }

    private static String keyFor() {
        return storage.newKey(WorkshopId.newId());
    }

    @Test
    @DisplayName("workshop.md §5: the key is {workshop_id}/{uuid}.pdf, fresh every time")
    void keyFormat() {
        var workshop = WorkshopId.newId();

        var first = storage.newKey(workshop);
        var second = storage.newKey(workshop);

        assertThat(first)
                .matches(
                        workshop.value()
                                + "/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.pdf");
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    @DisplayName(
            "§7: the browser uploads straight to the bucket with the presigned URL, and the object is there")
    void presignedPut_thenHeadOpenDelete() throws Exception {
        var key = keyFor();
        assertThat(storage.head(key)).as("nothing before the upload").isEmpty();

        var upload = storage.presignUpload(key, Duration.ofMinutes(10));
        assertThat(put(upload.url(), "application/pdf", PDF)).isEqualTo(200);

        assertThat(storage.head(key))
                .get()
                .extracting("contentLength")
                .isEqualTo((long) PDF.length);
        try (var object = storage.open(key)) {
            assertThat(object.contentLength()).isEqualTo(PDF.length);
            assertThat(object.stream().readAllBytes()).isEqualTo(PDF);
        }
        storage.delete(key);
        assertThat(storage.head(key)).isEmpty();
    }

    @Test
    @DisplayName("§7: the URL lives for the TTL it was asked for, ten minutes in use")
    void presignedUrl_expiresAtTheTtl() {
        var before = Instant.now();

        var upload = storage.presignUpload(keyFor(), Duration.ofMinutes(10));

        assertThat(upload.expiresAt())
                .isBetween(
                        before.plus(Duration.ofMinutes(10)).minusSeconds(5),
                        Instant.now().plus(Duration.ofMinutes(10)).plusSeconds(5));
        assertThat(upload.url().toString()).contains("X-Amz-Expires=600");
    }

    @Test
    @DisplayName("§7: a URL whose time has run out writes nothing")
    void expiredUrl_isRefused() throws Exception {
        var key = keyFor();
        var upload = storage.presignUpload(key, Duration.ofSeconds(1));
        Thread.sleep(2_500);

        assertThat(put(upload.url(), "application/pdf", PDF)).isEqualTo(403);
        assertThat(storage.head(key)).isEmpty();
    }

    @Test
    @DisplayName("§7: one URL, one key: the signature does not carry over to another object")
    void url_isScopedToOneKey() throws Exception {
        var signedFor = keyFor();
        var other = keyFor();
        var upload = storage.presignUpload(signedFor, Duration.ofMinutes(10));
        var forged = URI.create(upload.url().toString().replace(signedFor, other));

        assertThat(put(forged, "application/pdf", PDF)).isEqualTo(403);
        assertThat(storage.head(other)).isEmpty();
    }

    @Test
    @DisplayName("the URL is signed for application/pdf: a different Content-Type is refused")
    void url_isSignedForPdf() throws Exception {
        var key = keyFor();
        var upload = storage.presignUpload(key, Duration.ofMinutes(10));

        assertThat(put(upload.url(), "text/html", PDF)).isEqualTo(403);
        assertThat(storage.head(key)).isEmpty();
    }

    @Test
    @DisplayName("a key nothing was written to is absent, and opening it says so")
    void missingObject() {
        var key = keyFor();

        assertThat(storage.head(key)).isEmpty();
        assertThatThrownBy(() -> storage.open(key)).isInstanceOf(ObjectNotFoundException.class);
        storage.delete(key); // deleting what is not there is not an error
    }

    @Test
    @DisplayName(
            "a bucket that cannot be reached is StorageUnavailable, fast; presigning still works")
    void unreachableBucket() {
        var dead =
                new ObjectStorageProperties(
                        "http://localhost:1",
                        null,
                        null,
                        "k",
                        "s",
                        false,
                        Duration.ofMillis(500),
                        Duration.ofMillis(500));
        var config = new BrochureStorageConfig();
        try (var deadClient = config.brochureS3Client(dead);
                var deadPresigner = config.brochureS3Presigner(dead)) {
            var offline = new S3BrochureStorage(deadClient, deadPresigner, dead);
            var started = System.nanoTime();

            assertThatThrownBy(() -> offline.head("a/b.pdf"))
                    .isInstanceOf(StorageUnavailableException.class);
            assertThatThrownBy(() -> offline.open("a/b.pdf"))
                    .isInstanceOf(StorageUnavailableException.class);
            assertThatThrownBy(() -> offline.delete("a/b.pdf"))
                    .isInstanceOf(StorageUnavailableException.class);

            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .as("one attempt per call, short timeouts")
                    .isLessThan(Duration.ofSeconds(8));
            assertThat(
                            offline.presignUpload(
                                            "a/" + UUID.randomUUID() + ".pdf",
                                            Duration.ofMinutes(10))
                                    .url())
                    .as("presigning is local: it works with the bucket down")
                    .isNotNull();
        }
    }
}
