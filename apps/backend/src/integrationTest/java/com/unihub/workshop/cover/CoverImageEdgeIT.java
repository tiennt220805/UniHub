package com.unihub.workshop.cover;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.unihub.workshop.brochure.BrochureHttpSupport;
import com.unihub.workshop.infrastructure.config.BrochureStorageConfig;
import com.unihub.workshop.infrastructure.config.ObjectStorageProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import software.amazon.awssdk.core.sync.RequestBody;

/**
 * M-88, the backend half (Phase 8 tasks 3.4-3.5, D120-D122): what the bucket and the application
 * must get right for a browser on the application's own origin to upload and read covers through an
 * edge. The edge itself (Istio on the cluster, Vite in development) is proven where it runs: Phase
 * 8 tasks 3.5 and 9.2.
 *
 * <p>Two facts are proven here. First, SigV4 signs the {@code Host} and the path, so a presigned
 * URL minted for the browser-facing origin ({@code unihub.object-storage.public-endpoint}) works
 * through a proxy that keeps the {@code Host} as the browser sent it, and does not through one that
 * rewrites it: the same code, the one flag changed. Second, the bucket's policy: a cover is read
 * with no credentials, nothing else is, and nobody writes without a signature.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CoverImageEdgeIT extends BrochureHttpSupport {
    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final byte[] A_PNG = {
        (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'
    };

    private static final WireMockServer KEEPS_HOST = proxyToTheBucket(true);
    private static final WireMockServer REWRITES_HOST = proxyToTheBucket(false);

    private static WireMockServer proxyToTheBucket(boolean preserveHost) {
        var server =
                new WireMockServer(
                        WireMockConfiguration.options()
                                .dynamicPort()
                                .preserveHostHeader(preserveHost));
        server.start();
        server.stubFor(
                any(anyUrl())
                        .willReturn(
                                aResponse()
                                        .proxiedFrom(
                                                "http://"
                                                        + MINIO.getHost()
                                                        + ":"
                                                        + MINIO.getFirstMappedPort())));
        return server;
    }

    @DynamicPropertySource
    static void edge(DynamicPropertyRegistry registry) {
        // The application mints upload URLs for the proxy's address, as it does for the gateway's.
        registry.add(
                "unihub.object-storage.public-endpoint",
                () -> "http://localhost:" + KEEPS_HOST.port());
        registry.add("unihub.object-storage.create-bucket", () -> "true");
    }

    @AfterAll
    static void stopProxies() {
        KEEPS_HOST.stop();
        REWRITES_HOST.stop();
    }

    private static HttpResponse<byte[]> send(HttpRequest request) throws Exception {
        return CLIENT.send(request, BodyHandlers.ofByteArray());
    }

    /** An upload URL minted for {@code origin}, exactly as the application mints one. */
    private URI presignedPutFor(String origin, String key, String contentType, long length) {
        var settings =
                new ObjectStorageProperties(
                        MINIO.getS3URL(),
                        origin,
                        null,
                        storage.bucket(),
                        MINIO.getUserName(),
                        MINIO.getPassword(),
                        null,
                        null,
                        null);
        try (var presigner = new BrochureStorageConfig().brochureS3Presigner(settings)) {
            var presigned =
                    presigner.presignPutObject(
                            r ->
                                    r.signatureDuration(Duration.ofMinutes(5))
                                            .putObjectRequest(
                                                    p ->
                                                            p.bucket(storage.bucket())
                                                                    .key(key)
                                                                    .contentType(contentType)
                                                                    .contentLength(length)));
            return URI.create(presigned.url().toString());
        }
    }

    private static HttpRequest put(URI url, String contentType, byte[] body) {
        return HttpRequest.newBuilder(url)
                .header("Content-Type", contentType)
                .PUT(BodyPublishers.ofByteArray(body))
                .build();
    }

    private String aCoverKey() {
        return "covers/" + UUID.randomUUID() + "/" + UUID.randomUUID() + ".png";
    }

    @Test
    @DisplayName(
            "the application mints an upload URL for the browser's origin, and the PUT through a host-preserving proxy lands")
    void applicationUrlWorksThroughAProxyThatKeepsTheHost() throws Exception {
        var workshop = aWorkshop();
        var issued =
                post(
                        "/api/v1/workshops/" + workshop + "/cover-image-upload-url",
                        organizer(),
                        "{\"contentType\":\"image/png\",\"contentLength\":" + A_PNG.length + "}");
        var url = URI.create(json(issued).get("uploadUrl").asText());

        assertThat(url.getAuthority()).isEqualTo("localhost:" + KEEPS_HOST.port());
        assertThat(url.getPath()).isEqualTo("/" + storage.bucket() + "/" + pending(workshop));
        var response = send(put(url, "image/png", A_PNG));

        assertThat(response.statusCode()).as(new String(response.body())).isEqualTo(200);
        var confirmed =
                post(
                        "/api/v1/workshops/" + workshop + "/cover-image-upload-confirm",
                        organizer(),
                        null);
        assertThat(confirmed.getStatusCode().value()).isEqualTo(200);
    }

    private String pending(UUID workshop) {
        return jdbc.queryForObject(
                "SELECT pending_cover_image_key FROM workshops WHERE id = ?",
                String.class,
                workshop);
    }

    @Test
    @DisplayName(
            "control: the same URL minted the same way fails through a proxy that rewrites the Host (SigV4 signs it)")
    void aProxyThatRewritesTheHostBreaksTheSignature() throws Exception {
        var keeps = aCoverKey();
        var rewrites = aCoverKey();

        var kept =
                send(
                        put(
                                presignedPutFor(
                                        "http://localhost:" + KEEPS_HOST.port(),
                                        keeps,
                                        "image/png",
                                        A_PNG.length),
                                "image/png",
                                A_PNG));
        var rewritten =
                send(
                        put(
                                presignedPutFor(
                                        "http://localhost:" + REWRITES_HOST.port(),
                                        rewrites,
                                        "image/png",
                                        A_PNG.length),
                                "image/png",
                                A_PNG));

        assertThat(kept.statusCode()).isEqualTo(200);
        assertThat(rewritten.statusCode()).isEqualTo(403);
        assertThat(new String(rewritten.body())).contains("SignatureDoesNotMatch");
        assertThat(objectExists(keeps)).isTrue();
        assertThat(objectExists(rewrites)).isFalse();
    }

    @Test
    @DisplayName("a cover is read with no credentials, with the type it was stored as")
    void aCoverIsPubliclyReadable() throws Exception {
        var key = aCoverKey();
        s3.putObject(
                r -> r.bucket(storage.bucket()).key(key).contentType("image/png"),
                RequestBody.fromBytes(A_PNG));

        var response =
                send(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                MINIO.getS3URL()
                                                        + "/"
                                                        + storage.bucket()
                                                        + "/"
                                                        + key))
                                .build());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(A_PNG);
        assertThat(response.headers().firstValue("Content-Type")).contains("image/png");
    }

    @Test
    @DisplayName(
            "nothing else is read without credentials: not a brochure, not a listing of the bucket")
    void onlyCoversArePublic() throws Exception {
        var brochure = aConfirmedBrochure(A_PDF);
        var base = MINIO.getS3URL() + "/" + storage.bucket();

        var pdf = send(HttpRequest.newBuilder(URI.create(base + "/" + brochure.key())).build());
        var listing = send(HttpRequest.newBuilder(URI.create(base + "/?list-type=2")).build());
        var covers =
                send(
                        HttpRequest.newBuilder(URI.create(base + "/?list-type=2&prefix=covers/"))
                                .build());

        assertThat(pdf.statusCode()).isEqualTo(403);
        assertThat(listing.statusCode()).isEqualTo(403);
        assertThat(covers.statusCode()).as("listing the covers is not reading them").isEqualTo(403);
    }

    @Test
    @DisplayName("nobody writes or deletes without a signature, not even under covers/")
    void noAnonymousWrites() throws Exception {
        var key = aCoverKey();
        var base = MINIO.getS3URL() + "/" + storage.bucket() + "/" + key;

        var unsignedPut = send(put(URI.create(base), "image/png", A_PNG));
        s3.putObject(
                r -> r.bucket(storage.bucket()).key(key).contentType("image/png"),
                RequestBody.fromBytes(A_PNG));
        var unsignedDelete = send(HttpRequest.newBuilder(URI.create(base)).DELETE().build());

        assertThat(unsignedPut.statusCode()).isEqualTo(403);
        assertThat(unsignedDelete.statusCode()).isEqualTo(403);
        assertThat(objectExists(key)).isTrue();
    }
}
