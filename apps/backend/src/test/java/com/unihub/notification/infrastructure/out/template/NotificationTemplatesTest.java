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
                .contains("https://unihub.example/me/registrations");
        assertThat(email.html()).contains("href=\"https://unihub.example/me/registrations\"");
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
                .contains("https://unihub.example/me/registrations")
                .doesNotContain("//registrations");
    }

    // ---------- the e-ticket block (Phase 5 checklist D55, AC55.1, AC55.2) ----------

    private static final String QR_PAYLOAD =
            "eyJhbGciOiJFUzI1NiJ9.eyJ0aWNrZXRfY29kZSI6IlgifQ.c2lnbmF0dXJl";
    private static final String TICKET_URL =
            "https://unihub.example/me/registrations/11111111-2222-3333-4444-555555555555";

    private static Map<String, Object> contextWithTicket() {
        var context = context("Intro to Kubernetes");
        context.put("ticketQrPayload", QR_PAYLOAD);
        return context;
    }

    @Test
    @DisplayName(
            "a confirmation email with a ticket shows the QR inline and links to the e-ticket page")
    void confirmation_withATicket_hasTheQrAndTheLink() {
        var email =
                templates.render(
                        NotificationType.REGISTRATION_CONFIRMED, "EMAIL", contextWithTicket());

        assertThat(email.html())
                .contains("<img src=\"cid:ticket-qr\"")
                .contains("href=\"" + TICKET_URL + "\"");
        assertThat(email.text()).contains(TICKET_URL);
    }

    @Test
    @DisplayName("the QR payload is a credential: it is in no part of the rendered email")
    void theQrPayload_isNeverWrittenIntoTheContent() {
        var email =
                templates.render(
                        NotificationType.REGISTRATION_CONFIRMED, "EMAIL", contextWithTicket());
        var inApp =
                templates.render(
                        NotificationType.REGISTRATION_CONFIRMED, "IN_APP", contextWithTicket());

        for (var part :
                new String[] {
                    email.title(), email.text(), email.html(), inApp.title(), inApp.text()
                }) {
            assertThat(part).doesNotContain(QR_PAYLOAD).doesNotContain("eyJ");
        }
    }

    @Test
    @DisplayName(
            "a confirmation without a ticket has no image, no dangling cid, no ticket link, and the"
                    + " list link is still there (AC55.2)")
    void confirmation_withoutATicket_isTheSameEmailWithoutTheBlock() {
        var email =
                templates.render(
                        NotificationType.REGISTRATION_CONFIRMED,
                        "EMAIL",
                        context("Intro to Kubernetes"));

        assertThat(email.html())
                .doesNotContain("<img")
                .doesNotContain("cid:")
                .doesNotContain(TICKET_URL)
                .contains("href=\"https://unihub.example/me/registrations\"");
        assertThat(email.text())
                .doesNotContain(TICKET_URL)
                .contains("https://unihub.example/me/registrations");
    }

    @Test
    @DisplayName("a blank payload counts as no ticket")
    void aBlankPayload_isNoTicket() {
        var context = context("W");
        context.put("ticketQrPayload", "  ");

        var email = templates.render(NotificationType.REGISTRATION_CONFIRMED, "EMAIL", context);

        assertThat(email.html()).doesNotContain("<img").doesNotContain("cid:");
    }

    @Test
    @DisplayName(
            "without a ticket the template leaves exactly the line breaks it had before Phase 5")
    void withoutATicket_theTemplateLeavesNoGap() {
        var email =
                templates.render(NotificationType.REGISTRATION_CONFIRMED, "EMAIL", context("W"));

        assertThat(email.text())
                .contains("Mã đăng ký: 11111111-2222-3333-4444-555555555555\n\nXem");
        assertThat(email.html()).contains("</ul>\n<p><a href=");
    }

    @Test
    @DisplayName("the in-app notification and the other types never carry the block")
    void otherTypesAndChannels_areUnchangedByATicket() {
        var withTicket = context("W");
        withTicket.put("ticketQrPayload", QR_PAYLOAD);
        for (var type : NotificationType.values()) {
            if (type != NotificationType.REGISTRATION_CONFIRMED) {
                assertThat(templates.render(type, "EMAIL", withTicket))
                        .as(type + " email")
                        .isEqualTo(templates.render(type, "EMAIL", context("W")));
            }
            assertThat(templates.render(type, "IN_APP", withTicket))
                    .as(type + " in-app")
                    .isEqualTo(templates.render(type, "IN_APP", context("W")));
        }
    }

    @Test
    @DisplayName("a context key cannot smuggle markup in by being called ticketBlock")
    void ticketBlock_cannotBeSetFromTheContext() {
        var context = context("W");
        context.put("ticketBlock", "<script>steal()</script>");

        var email = templates.render(NotificationType.REGISTRATION_CONFIRMED, "EMAIL", context);

        assertThat(email.html()).doesNotContain("<script>");
        assertThat(email.text()).doesNotContain("steal()");
    }

    @Test
    @DisplayName("the e-ticket link has no double slash when the base URL ends with one")
    void ticketLink_trailingSlashIsIgnored() {
        var email =
                templates("https://unihub.example/", "UTC")
                        .render(
                                NotificationType.REGISTRATION_CONFIRMED,
                                "EMAIL",
                                contextWithTicket());

        assertThat(email.html()).contains(TICKET_URL).doesNotContain("//me/");
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
