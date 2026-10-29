package com.unihub.notification.infrastructure.out.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unihub.notification.domain.model.NotificationType;
import com.unihub.notification.infrastructure.config.NotificationProperties;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Notification content (notification.md §4, Phase 4 checklist D37: AC37.1-AC37.5). Rendering is a
 * pure function, so every {@code (type, channel)} pair is walked with a sample context and a
 * missing key is a failure here, not a broken message a student sees.
 */
class NotificationTemplatesTest {

    private static final String[] CHANNELS = {"EMAIL", "IN_APP"};

    private static ResourceNotificationTemplates templates(String baseUrl, String zone) {
        return new ResourceNotificationTemplates(
                new NotificationProperties(
                        new NotificationProperties.Smtp(
                                "localhost",
                                25,
                                null,
                                null,
                                "a@b.test",
                                false,
                                Duration.ofSeconds(5),
                                Duration.ofSeconds(10),
                                Duration.ofSeconds(10)),
                        Duration.ofSeconds(120),
                        ZoneId.of(zone),
                        new NotificationProperties.Listener(2, 1),
                        null),
                baseUrl);
    }

    private final ResourceNotificationTemplates templates =
            templates("https://unihub.example", "Asia/Ho_Chi_Minh");

    private static Map<String, Object> context(String workshopTitle) {
        var context = new HashMap<String, Object>();
        context.put("recipientName", "Nguyễn Văn An");
        context.put("workshopTitle", workshopTitle);
        context.put("startTime", Instant.parse("2026-11-01T01:00:00Z"));
        context.put("endTime", Instant.parse("2026-11-01T03:00:00Z"));
        context.put("location", "Phòng 101");
        context.put("registrationRef", "11111111-2222-3333-4444-555555555555");
        context.put("previousStartTime", Instant.parse("2026-10-30T01:00:00Z"));
        context.put("previousEndTime", Instant.parse("2026-10-30T03:00:00Z"));
        return context;
    }

    @Test
    @DisplayName("every (type, channel) renders fully: content, no placeholder left (AC37.2)")
    void everyPairRenders() {
        for (var type : NotificationType.values()) {
            for (var channel : CHANNELS) {
                var rendered = templates.render(type, channel, context("Intro to Kubernetes"));

                assertThat(rendered.title())
                        .as(type + "/" + channel + " title")
                        .isNotBlank()
                        .doesNotContain("{{");
                assertThat(rendered.text())
                        .as(type + "/" + channel + " text")
                        .isNotBlank()
                        .doesNotContain("{{");
                if (channel.equals("EMAIL")) {
                    assertThat(rendered.html()).isNotBlank().doesNotContain("{{");
                } else {
                    assertThat(rendered.html()).isNull();
                }
            }
        }
    }

    @Test
    @DisplayName(
            "the confirmation names the workshop, the time, the place, the reference and the link (AC37.1)")
    void confirmation_carriesWhatTheStudentNeeds() {
        var email =
                templates.render(
                        NotificationType.REGISTRATION_CONFIRMED,
                        "EMAIL",
                        context("Intro to Kubernetes"));

        assertThat(email.title()).contains("Intro to Kubernetes");
        assertThat(email.text())
                .contains("Intro to Kubernetes")
                .contains("Phòng 101")
                .contains("11111111-2222-3333-4444-555555555555")
                .contains("Nguyễn Văn An")
                .contains("https://unihub.example/registrations");
        assertThat(email.html()).contains("href=\"https://unihub.example/registrations\"");
    }

    @Test
    @DisplayName("a reschedule shows the old and the new time")
    void reschedule_showsBothTimes() {
        var email = templates.render(NotificationType.WORKSHOP_RESCHEDULED, "EMAIL", context("W"));

        assertThat(email.text()).contains("30/10/2026 08:00").contains("01/11/2026 08:00");
    }

    @Test
    @DisplayName("times are shown in the configured zone, not UTC or the server's (AC37.4)")
    void times_areInTheConfiguredZone() {
        var email = templates.render(NotificationType.WORKSHOP_CANCELLED, "EMAIL", context("W"));
        var utc =
                templates("https://unihub.example", "UTC")
                        .render(NotificationType.WORKSHOP_CANCELLED, "EMAIL", context("W"));

        assertThat(email.text()).contains("01/11/2026 08:00");
        assertThat(utc.text()).contains("01/11/2026 01:00");
    }

    @Test
    @DisplayName(
            "an organizer's markup is escaped in the HTML part and left alone in the text part (AC37.3)")
    void htmlPart_isEscaped() {
        var title = "<script>alert('x')</script> & \"co\"";
        var email = templates.render(NotificationType.WORKSHOP_CANCELLED, "EMAIL", context(title));

        assertThat(email.html())
                .doesNotContain("<script>")
                .contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt; &amp; &quot;co&quot;");
        assertThat(email.text()).contains(title);
    }

    @Test
    @DisplayName("a title with a line break stays on one line, so it cannot inject a header")
    void title_staysOnOneLine() {
        var email =
                templates.render(
                        NotificationType.WORKSHOP_CANCELLED,
                        "EMAIL",
                        context("A\r\nBcc: x@y.test"));

        assertThat(email.title()).doesNotContain("\n").doesNotContain("\r");
    }

    @Test
    @DisplayName(
            "an in-app title always fits VARCHAR(200): the workshop title is shortened with an ellipsis (AC37.5)")
    void inAppTitle_fitsTheColumn() {
        var longTitle = "x".repeat(255);
        for (var type : NotificationType.values()) {
            var rendered = templates.render(type, "IN_APP", context(longTitle));

            assertThat(rendered.title().codePointCount(0, rendered.title().length()))
                    .as(type.toString())
                    .isLessThanOrEqualTo(200);
            assertThat(rendered.title()).contains("…");
        }
    }

    @Test
    @DisplayName("shortening never splits a surrogate pair")
    void shortening_respectsCodePoints() {
        var emoji = "😀".repeat(255);

        var rendered =
                templates.render(NotificationType.WORKSHOP_CANCELLED, "IN_APP", context(emoji));

        assertThat(rendered.title().codePointCount(0, rendered.title().length()))
                .isLessThanOrEqualTo(200);
        assertThat(highSurrogates(rendered.title())).isEqualTo(lowSurrogates(rendered.title()));
    }

    @Test
    @DisplayName("a title that already fits is left as it is")
    void shortTitle_isUntouched() {
        var rendered =
                templates.render(NotificationType.WORKSHOP_CANCELLED, "IN_APP", context("Short"));

        assertThat(rendered.title()).isEqualTo("Workshop đã bị hủy: Short");
    }

    @Test
    @DisplayName("a key the template needs but the context lacks fails loudly")
    void missingKey_failsLoudly() {
        var context = context("W");
        context.remove("registrationRef");

        assertThatThrownBy(
                        () ->
                                templates.render(
                                        NotificationType.REGISTRATION_CONFIRMED, "EMAIL", context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("registrationRef");
    }

    @Test
    @DisplayName("the link works with or without a trailing slash on the base URL")
    void baseUrl_trailingSlashIsIgnored() {
        var email =
                templates("https://unihub.example/", "UTC")
                        .render(NotificationType.WORKSHOP_CANCELLED, "EMAIL", context("W"));

        assertThat(email.text())
                .contains("https://unihub.example/registrations")
                .doesNotContain("//registrations");
    }

    @Test
    @DisplayName("the same inputs always give the same content")
    void rendering_isPure() {
        var first = templates.render(NotificationType.WORKSHOP_RESCHEDULED, "EMAIL", context("W"));
        var second = templates.render(NotificationType.WORKSHOP_RESCHEDULED, "EMAIL", context("W"));

        assertThat(first).isEqualTo(second);
    }

    private static long highSurrogates(String value) {
        return value.chars().filter(c -> Character.isHighSurrogate((char) c)).count();
    }

    private static long lowSurrogates(String value) {
        return value.chars().filter(c -> Character.isLowSurrogate((char) c)).count();
    }
}
