package com.unihub.workshop.brochure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.shared.security.TestJwts;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** The two brochure endpoints over real HTTP, and the browser's own PUT to the issued URL. */
public abstract class BrochureHttpSupport extends BrochureITSupport {
    protected static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient BROWSER = HttpClient.newHttpClient();
    protected static final UUID ORGANIZER = UUID.randomUUID();

    @Autowired protected TestRestTemplate http;

    protected static String organizer() {
        return TestJwts.valid(ORGANIZER, "WORKSHOP_CREATE", "WORKSHOP_EDIT");
    }

    protected ResponseEntity<String> post(String path, String token, String body) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    protected ResponseEntity<String> issue(UUID workshop, String token) {
        return post(
                "/api/v1/workshops/" + workshop + "/brochure-upload-url",
                token,
                "{\"contentType\":\"application/pdf\"}");
    }

    protected ResponseEntity<String> confirm(UUID workshop, String token) {
        return post("/api/v1/workshops/" + workshop + "/brochure-upload-confirm", token, null);
    }

    protected static JsonNode json(ResponseEntity<String> response) {
        try {
            return JSON.readTree(response.getBody());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(response.getBody(), e);
        }
    }

    protected static URI uploadUrl(ResponseEntity<String> issued) {
        return URI.create(json(issued).get("uploadUrl").asText());
    }

    /** What the browser does with the issued URL. */
    protected static int put(URI url, byte[] pdf) throws Exception {
        var request =
                HttpRequest.newBuilder(url)
                        .header("Content-Type", "application/pdf")
                        .PUT(BodyPublishers.ofByteArray(pdf))
                        .build();
        return BROWSER.send(request, BodyHandlers.discarding()).statusCode();
    }

    protected String pendingKeyOf(UUID workshop) {
        return jdbc.queryForObject(
                "SELECT pending_brochure_key FROM workshops WHERE id = ?", String.class, workshop);
    }

    protected String brochureKeyOf(UUID workshop) {
        return jdbc.queryForObject(
                "SELECT brochure_s3_key FROM workshops WHERE id = ?", String.class, workshop);
    }

    protected int summarizationRequests(UUID workshop) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM event_publications WHERE aggregate_id = ? AND routing_key = ?",
                Integer.class,
                workshop,
                "unihub.workshop.summarization.requested");
    }
}
