package com.unihub.notification.infrastructure.out.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.unihub.notification.application.port.out.NotificationDeliveryResult;
import com.unihub.notification.application.port.out.NotificationDispatch;
import com.unihub.notification.application.port.out.RenderedNotification;
import com.unihub.notification.application.port.out.TicketQrImageException;
import com.unihub.notification.application.port.out.TicketQrImages;
import com.unihub.notification.domain.model.NotificationType;
import com.unihub.notification.infrastructure.config.NotificationProperties;
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.javamail.JavaMailSender;

class EmailNotificationChannelTest {

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final List<Map<String, Object>> renderedWith = new ArrayList<>();
    private TicketQrImages qrImages =
            payload -> {
                throw new UnsupportedOperationException("no ticket in these tests");
            };
    private final EmailNotificationChannel channel =
            new EmailNotificationChannel(
                    mailSender,
                    (type, code, context) -> {
                        renderedWith.add(context);
                        return new RenderedNotification("Subject", "text", "<p>html</p>");
                    },
                    payload -> qrImages.pngOf(payload),
                    new NotificationProperties(
                            new NotificationProperties.Smtp(
                                    "localhost",
                                    25,
                                    null,
                                    null,
                                    "noreply@unihub.test",
                                    false,
                                    java.time.Duration.ofSeconds(5),
                                    java.time.Duration.ofSeconds(10),
                                    java.time.Duration.ofSeconds(10)),
                            java.time.Duration.ofSeconds(120),
                            java.time.ZoneId.of("Asia/Ho_Chi_Minh"),
                            new NotificationProperties.Listener(2, 1),
                            null));

    private static NotificationDispatch to(String email) {
        return new NotificationDispatch(
                UUID.randomUUID(),
                UUID.randomUUID(),
                email,
                NotificationType.WORKSHOP_CANCELLED,
                Map.of());
    }

    private MimeMessage newMessage() {
        var message = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(message);
        return message;
    }

    @Test
    @DisplayName("the channel is EMAIL")
    void code() {
        assertThat(channel.channelCode()).isEqualTo("EMAIL");
    }

    @Test
    @DisplayName(
            "a good send builds a text+html message from the configured address and reports SENT")
    void send_succeeds() throws Exception {
        var message = newMessage();

        var result = channel.send(to("ann@example.test"));

        assertThat(result).isEqualTo(NotificationDeliveryResult.SENT);
        verify(mailSender).send(message);
        message.saveChanges();
        assertThat(message.getFrom()[0].toString()).isEqualTo("noreply@unihub.test");
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo("ann@example.test");
        assertThat(message.getSubject()).isEqualTo("Subject");
    }

    // ---------- the e-ticket QR (Phase 5 checklist D55) ----------

    private static NotificationDispatch confirmationWith(Map<String, Object> context) {
        return new NotificationDispatch(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "ann@example.test",
                NotificationType.REGISTRATION_CONFIRMED,
                context);
    }

    /** Every part of the message, nested multiparts walked. */
    private static List<Part> partsOf(Part part) throws Exception {
        var parts = new ArrayList<Part>();
        parts.add(part);
        if (part.getContent() instanceof Multipart multipart) {
            for (var i = 0; i < multipart.getCount(); i++) {
                BodyPart child = multipart.getBodyPart(i);
                parts.addAll(partsOf(child));
            }
        }
        return parts;
    }

    private static List<Part> imagesOf(MimeMessage message) throws Exception {
        message.saveChanges();
        return partsOf(message).stream()
                .filter(
                        part -> {
                            try {
                                return part.isMimeType("image/png");
                            } catch (Exception e) {
                                throw new IllegalStateException(e);
                            }
                        })
                .toList();
    }

    @Test
    @DisplayName(
            "a ticket in the context is drawn and attached inline under the Content-ID the HTML uses")
    void ticket_isAttachedInline() throws Exception {
        var message = newMessage();
        var png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
        qrImages = payload -> payload.equals("header.payload.sig") ? png : new byte[0];

        var result =
                channel.send(confirmationWith(Map.of("ticketQrPayload", "header.payload.sig")));

        assertThat(result).isEqualTo(NotificationDeliveryResult.SENT);
        var images = imagesOf(message);
        assertThat(images).hasSize(1);
        assertThat(images.getFirst().getHeader("Content-ID")).containsExactly("<ticket-qr>");
        assertThat(images.getFirst().getDisposition()).isEqualToIgnoringCase(Part.INLINE);
        assertThat(images.getFirst().getInputStream().readAllBytes()).isEqualTo(png);
        assertThat(renderedWith.getFirst()).containsKey("ticketQrPayload");
    }

    @Test
    @DisplayName("no ticket: nothing is drawn, nothing is attached, and it is still sent")
    void noTicket_noImage() throws Exception {
        var message = newMessage();

        var result = channel.send(confirmationWith(Map.of("recipientName", "Ann")));

        assertThat(result).isEqualTo(NotificationDeliveryResult.SENT);
        assertThat(imagesOf(message)).isEmpty();
    }

    @Test
    @DisplayName(
            "a payload that cannot be drawn: the email goes without the image and without a"
                    + " reference to it, still SENT (AC55.2)")
    void qrFailure_sendsWithoutTheImage() throws Exception {
        var message = newMessage();
        qrImages =
                payload -> {
                    throw new TicketQrImageException("too long");
                };

        var result = channel.send(confirmationWith(Map.of("ticketQrPayload", "x".repeat(10))));

        assertThat(result).isEqualTo(NotificationDeliveryResult.SENT);
        assertThat(imagesOf(message)).isEmpty();
        assertThat(renderedWith.getFirst())
                .as("the template is told there is no ticket, so it writes no cid: reference")
                .doesNotContainKey("ticketQrPayload");
        verify(mailSender).send(message);
    }

    @Test
    @DisplayName(
            "a blank payload is no ticket: the drawing is never asked and the template is told so")
    void blankPayload_isNotDrawn() throws Exception {
        var message = newMessage();
        qrImages =
                payload -> {
                    throw new AssertionError("a blank payload must not reach the drawing");
                };

        var result = channel.send(confirmationWith(Map.of("ticketQrPayload", " ")));

        assertThat(result).isEqualTo(NotificationDeliveryResult.SENT);
        assertThat(imagesOf(message)).isEmpty();
        assertThat(renderedWith.getFirst()).doesNotContainKey("ticketQrPayload");
    }

    @Test
    @DisplayName("a user with no address is rejected without touching the mail server")
    void noAddress_isRejected() {
        assertThat(channel.send(to(null))).isEqualTo(NotificationDeliveryResult.REJECTED);
        assertThat(channel.send(to("  "))).isEqualTo(NotificationDeliveryResult.REJECTED);
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("a malformed address is rejected for good: retrying cannot fix it")
    void malformedAddress_isRejected() {
        newMessage();

        assertThat(channel.send(to("not an address @@")))
                .isEqualTo(NotificationDeliveryResult.REJECTED);
    }

    @Test
    @DisplayName("what the mail library throws is mapped, never propagated")
    void failure_isMapped() {
        var message = newMessage();
        doThrow(new MailAuthenticationException("535 bad credentials"))
                .when(mailSender)
                .send(message);

        assertThat(channel.send(to("ann@example.test")))
                .isEqualTo(NotificationDeliveryResult.UNAVAILABLE);
    }
}
