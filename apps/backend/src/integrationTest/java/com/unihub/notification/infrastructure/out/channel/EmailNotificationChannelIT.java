package com.unihub.notification.infrastructure.out.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.notification.application.port.out.NotificationChannel;
import com.unihub.notification.application.port.out.NotificationDeliveryResult;
import com.unihub.notification.application.port.out.NotificationDispatch;
import com.unihub.notification.application.port.out.NotificationTemplates;
import com.unihub.notification.domain.model.NotificationType;
import com.unihub.notification.infrastructure.config.NotificationProperties;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.infrastructure.Mailpit;
import com.unihub.shared.security.TestJwts;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The EMAIL channel against a real SMTP server, Mailpit (Phase 4 checklist D36): a message really
 * arrives with its content; with no SMTP server, or one that never answers, the channel reports
 * {@code UNAVAILABLE} within its timeouts instead of hanging a listener thread (AC36.2). The
 * refusal codes Mailpit cannot be told to produce are proved in {@code SmtpFailuresTest}.
 */
@SpringBootTest
class EmailNotificationChannelIT extends IntegrationTestSupport {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> TestJwts.SECRET);
        // short, so the hung-server case takes seconds; the production values are 5s/10s/10s
        registry.add("unihub.notification.smtp.read-timeout", () -> "PT1S");
        registry.add("unihub.notification.smtp.write-timeout", () -> "PT1S");
    }

    @Autowired private List<NotificationChannel> channels;
    @Autowired private NotificationTemplates templates;
    @Autowired private NotificationProperties properties;

    private NotificationChannel email() {
        return channels.stream()
                .filter(c -> c.channelCode().equals("EMAIL"))
                .findFirst()
                .orElseThrow();
    }

    @BeforeEach
    void clearMailbox() {
        Mailpit.clear();
    }

    private static NotificationDispatch dispatchTo(String address) {
        return new NotificationDispatch(
                UUID.randomUUID(),
                UUID.randomUUID(),
                address,
                NotificationType.REGISTRATION_CONFIRMED,
                Map.of(
                        "recipientName", "Nguyễn Văn An",
                        "workshopTitle", "Intro to Kubernetes",
                        "startTime", Instant.parse("2026-12-01T01:00:00Z"),
                        "endTime", Instant.parse("2026-12-01T03:00:00Z"),
                        "location", "Phòng 101",
                        "registrationRef", "ref-123"));
    }

    @Test
    @DisplayName("a confirmation arrives in the mailbox with subject, text and HTML (AC37.1)")
    void email_arrives() {
        var address = "ann-" + UUID.randomUUID() + "@example.test";

        var result = email().send(dispatchTo(address));

        assertThat(result).isEqualTo(NotificationDeliveryResult.SENT);
        var mails = Mailpit.to(address);
        assertThat(mails).hasSize(1);
        var mail = mails.getFirst();
        assertThat(mail.subject()).isEqualTo("Xác nhận đăng ký: Intro to Kubernetes");
        assertThat(mail.text())
                .contains("Intro to Kubernetes")
                .contains("ref-123")
                .contains("08:00");
        assertThat(mail.html()).contains("<strong>Intro to Kubernetes</strong>");
        assertThat(mail.from()).isEqualTo("noreply@unihub.local");
    }

    @Test
    @DisplayName("with no SMTP server listening the channel is UNAVAILABLE, quickly (AC32.2 input)")
    void noServer_isUnavailable() {
        var channel = channelTo(deadPort(), Duration.ofSeconds(1));

        assertThat(channel.send(dispatchTo("ann@example.test")))
                .isEqualTo(NotificationDeliveryResult.UNAVAILABLE);
    }

    @Test
    @DisplayName(
            "a server that accepts the connection and never answers is UNAVAILABLE within the timeout (AC36.2)")
    void hungServer_isUnavailableWithinTheTimeout() throws Exception {
        try (var silent = new ServerSocket(0)) {
            var channel = channelTo(silent.getLocalPort(), Duration.ofSeconds(1));
            var started = System.nanoTime();

            var result = channel.send(dispatchTo("ann@example.test"));

            assertThat(result).isEqualTo(NotificationDeliveryResult.UNAVAILABLE);
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .isLessThan(Duration.ofSeconds(10));
        }
    }

    /** The production channel class, over a sender pointed at another server. */
    private NotificationChannel channelTo(int port, Duration timeout) {
        var sender = new JavaMailSenderImpl();
        sender.setHost("127.0.0.1");
        sender.setPort(port);
        var mail = new Properties();
        mail.put("mail.smtp.connectiontimeout", String.valueOf(timeout.toMillis()));
        mail.put("mail.smtp.timeout", String.valueOf(timeout.toMillis()));
        mail.put("mail.smtp.writetimeout", String.valueOf(timeout.toMillis()));
        sender.setJavaMailProperties(mail);
        return new EmailNotificationChannel(sender, templates, properties);
    }

    private static int deadPort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
