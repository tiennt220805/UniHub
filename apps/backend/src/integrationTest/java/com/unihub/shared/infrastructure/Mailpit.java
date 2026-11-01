package com.unihub.shared.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the singleton Mailpit's HTTP API (Phase 4 checklist D36): what the EMAIL channel actually
 * sent, counted as effects (M-37, M-40). Mailpit is shared by every test in the JVM, so a test
 * filters by recipient address, which it makes unique.
 */
public final class Mailpit {

    /** An image the HTML refers to as {@code cid:}, with the bytes Mailpit kept of it. */
    public record InlineImage(String contentId, String contentType, byte[] bytes) {}

    /** One caught message. {@code inline} is empty for a message without inline parts. */
    public record Mail(
            String id,
            String subject,
            String to,
            String from,
            String text,
            String html,
            List<InlineImage> inline) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private Mailpit() {}

    private static String base() {
        return "http://"
                + IntegrationTestSupport.MAILPIT.getHost()
                + ":"
                + IntegrationTestSupport.MAILPIT.getMappedPort(8025);
    }

    /** Everything caught, newest first (the newest 1000 at most: prefer {@link #to}). */
    public static List<Mail> all() {
        return fetch("/api/v1/messages?limit=1000");
    }

    /**
     * The messages addressed to {@code address}, asked of Mailpit's own search so that a test is
     * not slowed, or silently truncated, by the mail every other test left behind.
     */
    public static List<Mail> to(String address) {
        var query = URLEncoder.encode("to:" + address, StandardCharsets.UTF_8);
        return fetch("/api/v1/search?limit=500&query=" + query).stream()
                .filter(mail -> mail.to().equalsIgnoreCase(address))
                .toList();
    }

    /**
     * The recipient addresses of every message whose subject contains {@code token}, read from the
     * search summaries without fetching each message (a fan-out is counted by the hundred). Use a
     * unique ASCII token.
     */
    public static List<String> recipientsOfSubject(String token) {
        var query = URLEncoder.encode("subject:\"" + token + "\"", StandardCharsets.UTF_8);
        var addresses = new ArrayList<String>();
        for (var summary : get("/api/v1/search?limit=1000&query=" + query).path("messages")) {
            addresses.add(summary.path("To").path(0).path("Address").asText());
        }
        return addresses;
    }

    private static List<Mail> fetch(String listPath) {
        var list = get(listPath);
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
                            full.path("HTML").asText(),
                            inlineImagesOf(full)));
        }
        return mails;
    }

    /** Only a message that has inline parts costs a request for them. */
    private static List<InlineImage> inlineImagesOf(JsonNode message) {
        var images = new ArrayList<InlineImage>();
        for (var part : message.path("Inline")) {
            images.add(
                    new InlineImage(
                            part.path("ContentID").asText(),
                            part.path("ContentType").asText(),
                            bytes(
                                    "/api/v1/message/"
                                            + message.path("ID").asText()
                                            + "/part/"
                                            + part.path("PartID").asText())));
        }
        return images;
    }

    private static byte[] bytes(String path) {
        try {
            var response =
                    HTTP.send(
                            HttpRequest.newBuilder(URI.create(base() + path)).GET().build(),
                            HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException(
                        "Mailpit " + path + " answered " + response.statusCode());
            }
            return response.body();
        } catch (IOException e) {
            throw new IllegalStateException("Mailpit is unreachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while reading Mailpit", e);
        }
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
