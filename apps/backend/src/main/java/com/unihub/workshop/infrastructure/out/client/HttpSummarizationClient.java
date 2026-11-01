package com.unihub.workshop.infrastructure.out.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.workshop.application.port.out.SummarizationClient;
import com.unihub.workshop.infrastructure.config.AiSummarizationProperties;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The AI Summarization Service over HTTPS (workshop.md §9): {@code POST {base-url}/v1/summarize},
 * {@code multipart/form-data} with one part {@code file}, a static bearer key, 15 s, no retry. The
 * PDF is streamed from the bucket's response; it is never held a second time in a byte array. Knows
 * nothing of any provider: the base URL, the key and the timeout are configuration.
 *
 * <p>Answers: {@code 200} with {@code {"summary": "..."}} is a summary; {@code 422} is terminal for
 * this brochure; every other outcome is {@link SummarizationUnavailableException}, which the
 * consumer lets walk its retry chain.
 */
@Component
class HttpSummarizationClient implements SummarizationClient {
    private static final String BOUNDARY_PREFIX = "unihub-";

    /** An answer larger than this is not a summary (it is cut to 500 characters anyway). */
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    private final AiSummarizationProperties settings;
    private final ObjectMapper json;
    private final HttpClient http;

    HttpSummarizationClient(AiSummarizationProperties settings, ObjectMapper json) {
        this.settings = settings;
        this.json = json;
        this.http =
                HttpClient.newBuilder()
                        .connectTimeout(settings.timeout())
                        // An AI service that redirects is not one we follow with a bearer key.
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
    }

    @Override
    public SummaryOutcome summarize(InputStream pdf, long contentLength) {
        if (settings.baseUrl() == null) {
            throw new SummarizationUnavailableException(
                    "no AI summarization service is configured");
        }
        var boundary = BOUNDARY_PREFIX + UUID.randomUUID();
        var head =
                ("--"
                                + boundary
                                + "\r\nContent-Disposition: form-data; name=\"file\";"
                                + " filename=\"brochure.pdf\"\r\nContent-Type: application/pdf\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII);
        var tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII);
        var body =
                BodyPublishers.fromPublisher(
                        concat(
                                BodyPublishers.ofByteArray(head),
                                BodyPublishers.ofInputStream(() -> pdf),
                                BodyPublishers.ofByteArray(tail)),
                        head.length + contentLength + tail.length);

        var request =
                HttpRequest.newBuilder(URI.create(settings.baseUrl() + "/v1/summarize"))
                        .timeout(settings.timeout())
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .header("Accept", "application/json")
                        .POST(body);
        if (settings.apiKey() != null) {
            request.header("Authorization", "Bearer " + settings.apiKey());
        }

        HttpResponse<InputStream> response;
        try {
            response = http.send(request.build(), BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new SummarizationUnavailableException("the AI service could not be reached", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SummarizationUnavailableException("interrupted while waiting for the AI", e);
        }
        try (var answer = response.body()) {
            var status = response.statusCode();
            if (status == 422) {
                return new SummaryOutcome.Unsupported();
            }
            if (status != 200) {
                throw new SummarizationUnavailableException("the AI service answered " + status);
            }
            return parse(answer.readNBytes(MAX_RESPONSE_BYTES));
        } catch (IOException e) {
            throw new SummarizationUnavailableException("the AI answer could not be read", e);
        }
    }

    private SummaryOutcome parse(byte[] answer) {
        try {
            JsonNode summary = json.readTree(answer).path("summary");
            if (!summary.isTextual()) {
                throw new SummarizationUnavailableException("the AI answer has no summary");
            }
            var text = summary.asText().strip();
            return text.isEmpty()
                    ? new SummaryOutcome.Unsupported()
                    : new SummaryOutcome.Summary(text);
        } catch (IOException e) {
            throw new SummarizationUnavailableException("the AI answer is not JSON", e);
        }
    }

    /** Publishes the three parts one after the other, as one request body. */
    private static java.net.http.HttpRequest.BodyPublisher concat(
            java.net.http.HttpRequest.BodyPublisher... parts) {
        return BodyPublishers.concat(parts);
    }
}
