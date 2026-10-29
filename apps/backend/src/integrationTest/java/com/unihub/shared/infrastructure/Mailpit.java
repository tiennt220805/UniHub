package com.unihub.shared.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the singleton Mailpit's HTTP API (Phase 4 checklist D36): what the EMAIL channel actually
 * sent, counted as effects (M-37, M-40). Mailpit is shared by every test in the JVM, so a test
 * filters by recipient address, which it makes unique.
 */
public final class Mailpit {

    /** One caught message. */
    public record Mail(
            String id, String subject, String to, String from, String text, String html) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private Mailpit() {}

    private static String base() {
        return "http://"
                + IntegrationTestSupport.MAILPIT.getHost()
                + ":"
                + IntegrationTestSupport.MAILPIT.getMappedPort(8025);
    }

    /** Everything caught, newest first. */
    public static List<Mail> all() {
        var list = get("/api/v1/messages?limit=1000");
        var mails = new ArrayList<Mail>();
        for (var summary : list.path("messages")) {
            var full = get("/api/v1/message/" + summary.path("ID").asText());
            mails.add(
                    new Mail(
                            full.path("ID").asText(),
                            full.path("Subject").asText(),
                            full.path("To").path(0).path("Address").asText(),
                            full.path("From").path("Address").asText(),
                            full.path("Text").asText(),
                            full.path("HTML").asText()));
        }
        return mails;
    }

    /** The messages addressed to {@code address}. */
    public static List<Mail> to(String address) {
        return all().stream().filter(mail -> mail.to().equalsIgnoreCase(address)).toList();
    }

    public static void clear() {
        send(HttpRequest.newBuilder(URI.create(base() + "/api/v1/messages")).DELETE().build());
    }

    private static JsonNode get(String path) {
        var body = send(HttpRequest.newBuilder(URI.create(base() + path)).GET().build());
        try {
            return JSON.readTree(body);
        } catch (IOException e) {
            throw new IllegalStateException("Mailpit answered something that is not JSON", e);
        }
    }

    private static String send(HttpRequest request) {
        try {
            var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException(
                        "Mailpit " + request.uri() + " answered " + response.statusCode());
            }
            return response.body();
        } catch (IOException e) {
            throw new IllegalStateException("Mailpit is unreachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while reading Mailpit", e);
        }
    }
}
