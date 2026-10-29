package com.unihub.notification.infrastructure.out.template;

import com.unihub.notification.application.port.out.NotificationTemplates;
import com.unihub.notification.application.port.out.RenderedNotification;
import com.unihub.notification.domain.model.NotificationType;
import com.unihub.notification.infrastructure.config.NotificationProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Notification content from templates that live as resources in this module (notification.md §4,
 * Phase 4 checklist D37): Vietnamese, one set per {@code (type, channel)}, plain placeholder
 * substitution ({@code {{name}}}), no templating library.
 *
 * <p>Rendering is a pure function of {@code (type, channel, context)}. A key a template needs but
 * the context lacks is an {@link IllegalArgumentException} (a template/consumer mismatch, caught in
 * testing). Values are escaped in the HTML part, since a workshop title is organizer input; times
 * are shown in the configured zone, never the server's; the "my registrations" link is built from
 * {@code unihub.web.base-url}. An in-app title always fits {@code notifications.title} ({@code
 * VARCHAR(200)}) by shortening the workshop title with an ellipsis.
 */
@Component
class ResourceNotificationTemplates implements NotificationTemplates {

    static final int MAX_TITLE_CODE_POINTS = 200;
    private static final String ELLIPSIS = "…";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z]+)}}");
    private static final String BASE = "notification/templates/";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final ZoneId zone;
    private final String myRegistrationsUrl;
    private final Map<String, String> cache = new HashMap<>();

    ResourceNotificationTemplates(
            NotificationProperties properties,
            @Value("${unihub.web.base-url:http://localhost:5173}") String webBaseUrl) {
        this.zone = properties.displayZone();
        var base =
                webBaseUrl.endsWith("/")
                        ? webBaseUrl.substring(0, webBaseUrl.length() - 1)
                        : webBaseUrl;
        this.myRegistrationsUrl = base + "/registrations";
    }

    @Override
    public RenderedNotification render(
            NotificationType type, String channelCode, Map<String, Object> context) {
        var values = displayValues(context);
        var title = fill(template(type, channelCode, "title"), values, false);
        if ("IN_APP".equals(channelCode)) {
            title = fitTitle(type, channelCode, values, title);
        }
        var text = fill(template(type, channelCode, "text"), values, false);
        var html =
                "EMAIL".equals(channelCode)
                        ? fill(template(type, channelCode, "html"), values, true)
                        : null;
        return new RenderedNotification(singleLine(title), text, html);
    }

    // ---------- values ----------

    private Map<String, String> displayValues(Map<String, Object> context) {
        var values = new HashMap<String, String>();
        context.forEach(
                (key, value) -> {
                    if (value instanceof Instant instant) {
                        values.put(key, TIME.format(instant.atZone(zone)));
                    } else if (value != null) {
                        values.put(key, value.toString());
                    }
                });
        values.put("myRegistrationsUrl", myRegistrationsUrl);
        return values;
    }

    private static String fill(String template, Map<String, String> values, boolean escapeHtml) {
        var out = new StringBuilder();
        var matcher = PLACEHOLDER.matcher(template);
        var last = 0;
        while (matcher.find()) {
            out.append(template, last, matcher.start());
            var value = values.get(matcher.group(1));
            if (value == null) {
                throw new IllegalArgumentException(
                        "the template needs '" + matcher.group(1) + "' but the context has none");
            }
            out.append(escapeHtml ? escape(value) : value);
            last = matcher.end();
        }
        return out.append(template, last, template.length()).toString();
    }

    private static String escape(String value) {
        var out = new StringBuilder(value.length() + 16);
        for (var i = 0; i < value.length(); i++) {
            var c = value.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /** A header must stay on one line, whatever an organizer typed into a workshop title. */
    private static String singleLine(String value) {
        return value.replaceAll("[\\r\\n]+", " ");
    }

    // ---------- in-app title length ----------

    /**
     * Shortens the interpolated workshop title (not the template's own words) so the whole title is
     * at most {@value #MAX_TITLE_CODE_POINTS} code points, never splitting a surrogate pair. A
     * title that did not fit would fail the insert on every attempt and park the message.
     */
    private String fitTitle(
            NotificationType type, String channelCode, Map<String, String> values, String title) {
        var length = title.codePointCount(0, title.length());
        if (length <= MAX_TITLE_CODE_POINTS) {
            return title;
        }
        var workshopTitle = values.get("workshopTitle");
        if (workshopTitle == null) {
            return cutWithEllipsis(title, MAX_TITLE_CODE_POINTS);
        }
        var shared = new HashMap<>(values);
        var keep =
                workshopTitle.codePointCount(0, workshopTitle.length())
                        - (length - MAX_TITLE_CODE_POINTS)
                        - 1;
        shared.put("workshopTitle", cutWithEllipsis(workshopTitle, Math.max(keep + 1, 1)));
        var fitted = fill(template(type, channelCode, "title"), shared, false);
        return fitted.codePointCount(0, fitted.length()) <= MAX_TITLE_CODE_POINTS
                ? fitted
                : cutWithEllipsis(fitted, MAX_TITLE_CODE_POINTS);
    }

    /** At most {@code limit} code points, the last of them an ellipsis when something was cut. */
    private static String cutWithEllipsis(String value, int limit) {
        if (value.codePointCount(0, value.length()) <= limit) {
            return value;
        }
        var end = value.offsetByCodePoints(0, limit - 1);
        return value.substring(0, end) + ELLIPSIS;
    }

    // ---------- resources ----------

    private synchronized String template(NotificationType type, String channelCode, String part) {
        var name = BASE + type.name() + "." + channelCode + "." + part + ".tpl";
        return cache.computeIfAbsent(name, ResourceNotificationTemplates::load);
    }

    private static String load(String name) {
        var resource = new ClassPathResource(name);
        if (!resource.exists()) {
            throw new IllegalArgumentException("no notification template " + name);
        }
        try (var in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).stripTrailing();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
