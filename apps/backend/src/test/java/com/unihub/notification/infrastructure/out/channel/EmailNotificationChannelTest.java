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
import com.unihub.notification.domain.model.NotificationType;
import com.unihub.notification.infrastructure.config.NotificationProperties;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.javamail.JavaMailSender;

class EmailNotificationChannelTest {

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final EmailNotificationChannel channel =
            new EmailNotificationChannel(
                    mailSender,
                    (type, code, context) ->
                            new RenderedNotification("Subject", "text", "<p>html</p>"),
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
