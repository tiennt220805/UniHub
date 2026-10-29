package com.unihub.notification.infrastructure.out.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.notification.application.port.out.NotificationDeliveryResult;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Map;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;

/**
 * The SMTP failure table of notification.md §2 (Phase 4 checklist D32, D36, AC36.5), on exceptions
 * built the way {@code JavaMailSenderImpl} really wraps them: the per-recipient reply code is
 * buried in a {@code SendFailedException}'s next-exception chain inside a {@code
 * MailSendException}. Mailpit cannot be made to refuse a recipient or an authentication, so this is
 * where those cases are proved.
 */
class SmtpFailuresTest {

    /** What Jakarta Mail throws when RCPT TO is refused, wrapped as Spring wraps it. */
    private static MailSendException recipientRefused(int code) throws Exception {
        var address = new InternetAddress("someone@example.test");
        var perAddress =
                new SMTPAddressFailedException(address, "RCPT TO", code, code + " refused");
        var send =
                new SendFailedException(
                        "Invalid Addresses",
                        perAddress,
                        new InternetAddress[0],
                        new InternetAddress[0],
                        new InternetAddress[] {address});
        return new MailSendException(Map.of("message", send));
    }

    @ParameterizedTest(name = "RCPT TO answered {0} → {1}")
    @DisplayName("a reply to one recipient: 5xx is a permanent refusal, 4xx a transient one")
    @CsvSource({
        "550, REJECTED",
        "553, REJECTED",
        "554, REJECTED",
        "450, DEFERRED",
        "451, DEFERRED",
        "452, DEFERRED"
    })
    void recipientReply_isClassifiedByItsCode(int code, NotificationDeliveryResult expected)
            throws Exception {
        assertThat(SmtpFailures.classify(recipientRefused(code))).isEqualTo(expected);
    }

    @Test
    @DisplayName("an authentication failure is the channel's, even though it is a 5xx (535)")
    void authenticationFailure_isUnavailable() {
        assertThat(
                        SmtpFailures.classify(
                                new MailAuthenticationException("535 5.7.8 bad credentials")))
                .isEqualTo(NotificationDeliveryResult.UNAVAILABLE);
        assertThat(
                        SmtpFailures.classify(
                                new MailSendException(
                                        "failed",
                                        new AuthenticationFailedException(
                                                "535 authentication failed"))))
                .isEqualTo(NotificationDeliveryResult.UNAVAILABLE);
    }

    @Test
    @DisplayName(
            "a refusal that is not tied to a recipient (relay denied after MAIL FROM) is the channel's")
    void sendFailedWithoutAnAddress_isUnavailable() {
        var relayDenied =
                new SMTPSendFailedException(
                        "MAIL FROM", 550, "relay access denied", null, null, null, null);

        assertThat(SmtpFailures.classify(new MailSendException(Map.of("m", relayDenied))))
                .isEqualTo(NotificationDeliveryResult.UNAVAILABLE);
    }

    @Test
    @DisplayName("connection refused and timeouts are the channel's")
    void connectionProblems_areUnavailable() {
        assertThat(
                        SmtpFailures.classify(
                                new MailSendException(
                                        "Mail server connection failed",
                                        new MessagingException(
                                                "Could not connect",
                                                new ConnectException("refused")))))
                .isEqualTo(NotificationDeliveryResult.UNAVAILABLE);
        assertThat(
                        SmtpFailures.classify(
                                new MailSendException(
                                        "timeout",
                                        new MessagingException(
                                                "read",
                                                new SocketTimeoutException("Read timed out")))))
                .isEqualTo(NotificationDeliveryResult.UNAVAILABLE);
    }

    @Test
    @DisplayName(
            "anything unrecognised is the channel's, never a permanent verdict on one recipient")
    void unknownFailure_isUnavailable() {
        assertThat(SmtpFailures.classify(new IllegalStateException("surprise")))
                .isEqualTo(NotificationDeliveryResult.UNAVAILABLE);
    }
}
