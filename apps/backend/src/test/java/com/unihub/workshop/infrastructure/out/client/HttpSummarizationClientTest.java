package com.unihub.workshop.infrastructure.out.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.unihub.workshop.application.port.out.SummarizationClient.SummarizationUnavailableException;
import com.unihub.workshop.application.port.out.SummarizationClient.SummaryOutcome;
import com.unihub.workshop.infrastructure.config.AiSummarizationProperties;
import io.micrometer.observation.ObservationRegistry;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The AI service contract (workshop.md §9) against a WireMock stand-in: the request it makes, the
 * three kinds of answer, and that nothing is retried inside the call.
 */
class HttpSummarizationClientTest {
    private static final String PATH = "/v1/summarize";
    private static final byte[] PDF =
            "%PDF-1.7\nbody bytes\n%%EOF".getBytes(StandardCharsets.US_ASCII);
    private static WireMockServer service;

    @BeforeAll
    static void start() {
        service = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        service.start();
    }

    @AfterAll
    static void stop() {
        service.stop();
    }

    @BeforeEach
    void reset() {
        service.resetAll();
    }

    private HttpSummarizationClient client(String apiKey, Duration timeout) {
        return new HttpSummarizationClient(
                new AiSummarizationProperties(
                        "http://localhost:" + service.port(), apiKey, timeout),
                new ObjectMapper(),
                ObservationRegistry.NOOP);
    }

    private SummaryOutcome call(HttpSummarizationClient client) {
        return client.summarize(new ByteArrayInputStream(PDF), PDF.length);
    }

    private void answer(int status, String body) {
        service.stubFor(
                post(urlEqualTo(PATH))
                        .willReturn(
                                aResponse()
                                        .withStatus(status)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(body)));
    }

    @Test
    @DisplayName("a 200 with a summary is a summary")
    void summary() {
        answer(200, "{\"summary\":\"Một bản tóm tắt.\"}");

        assertThat(call(client("k", null)))
                .isEqualTo(new SummaryOutcome.Summary("Một bản tóm tắt."));
    }

    @Test
    @DisplayName(
            "the request is a multipart POST with one part `file` holding the PDF bytes, with the bearer key")
    void requestShape() {
        answer(200, "{\"summary\":\"x\"}");

        call(client("the-key", null));

        service.verify(
                1,
                postRequestedFor(urlEqualTo(PATH))
                        .withHeader("Authorization", equalTo("Bearer the-key"))
                        .withHeader("Content-Type", matching("multipart/form-data; boundary=.+"))
                        .withRequestBody(containing("name=\"file\""))
                        .withRequestBody(containing("Content-Type: application/pdf"))
                        .withRequestBody(containing("%PDF-1.7\nbody bytes\n%%EOF")));
    }

    @Test
    @DisplayName("without an API key no Authorization header is sent")
    void noKey() {
        answer(200, "{\"summary\":\"x\"}");

        call(client(null, null));

        assertThat(service.getAllServeEvents())
                .allSatisfy(
                        e -> assertThat(e.getRequest().containsHeader("Authorization")).isFalse());
    }

    @Test
    @DisplayName("a 422 is terminal for this brochure, not a failure")
    void unprocessable() {
        answer(422, "{\"reason\":\"UNSUPPORTED_CONTENT\"}");

        assertThat(call(client("k", null))).isInstanceOf(SummaryOutcome.Unsupported.class);
    }

    @Test
    @DisplayName("an empty summary is treated as nothing to store")
    void emptySummary() {
        answer(200, "{\"summary\":\"   \"}");

        assertThat(call(client("k", null))).isInstanceOf(SummaryOutcome.Unsupported.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 408, 429, 500, 502, 503, 504})
    @DisplayName("every other status is a failure that may succeed later, and is not retried here")
    void otherStatusesAreTransient(int status) {
        answer(status, "{}");

        assertThatThrownBy(() -> call(client("k", null)))
                .isInstanceOf(SummarizationUnavailableException.class);
        service.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not json", "[]", "{}", "{\"summary\":42}", "{\"summary\":null}"})
    @DisplayName("a 200 that does not carry a text summary is a failure, not an empty summary")
    void malformedAnswer(String body) {
        answer(200, body);

        assertThatThrownBy(() -> call(client("k", null)))
                .isInstanceOf(SummarizationUnavailableException.class);
    }

    @Test
    @DisplayName("a service that does not answer within the timeout is a failure")
    void timeout() {
        service.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withFixedDelay(1500)));

        assertThatThrownBy(() -> call(client("k", Duration.ofMillis(300))))
                .isInstanceOf(SummarizationUnavailableException.class);
    }

    @Test
    @DisplayName("a service that cannot be reached is a failure")
    void connectionRefused() {
        var dead =
                new HttpSummarizationClient(
                        new AiSummarizationProperties(
                                "http://localhost:1", "k", Duration.ofSeconds(2)),
                        new ObjectMapper(),
                        ObservationRegistry.NOOP);

        assertThatThrownBy(() -> call(dead)).isInstanceOf(SummarizationUnavailableException.class);
    }

    @Test
    @DisplayName("a redirect is not followed: the bearer key goes to the configured host only")
    void redirectsAreNotFollowed() {
        service.stubFor(
                post(urlEqualTo(PATH))
                        .willReturn(
                                aResponse()
                                        .withStatus(302)
                                        .withHeader("Location", "http://localhost:1/steal")));

        assertThatThrownBy(() -> call(client("k", null)))
                .isInstanceOf(SummarizationUnavailableException.class);
    }

    @Test
    @DisplayName("with no base URL configured nothing is sent and the call fails")
    void notConfigured() {
        var unconfigured =
                new HttpSummarizationClient(
                        new AiSummarizationProperties(null, null, null),
                        new ObjectMapper(),
                        ObservationRegistry.NOOP);

        assertThatThrownBy(() -> call(unconfigured))
                .isInstanceOf(SummarizationUnavailableException.class)
                .hasMessageContaining("configured");
        assertThat(service.getAllServeEvents()).isEmpty();
    }
}
