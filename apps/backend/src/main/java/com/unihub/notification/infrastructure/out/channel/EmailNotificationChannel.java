package com.unihub.notification.infrastructure.out.channel;

import com.unihub.notification.application.port.out.NotificationChannel;
import com.unihub.notification.application.port.out.NotificationDeliveryResult;
import com.unihub.notification.application.port.out.NotificationDispatch;
import com.unihub.notification.application.port.out.NotificationTemplates;
import com.unihub.notification.infrastructure.config.NotificationProperties;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.AddressException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Email over SMTP (notification.md §2, Phase 4 checklist D36): plain text plus a minimal HTML
 * alternative, vendor-neutral. Never throws for a delivery failure; {@link SmtpFailures} turns
 * whatever the mail library threw into a result.
 */
@Component
class EmailNotificationChannel implements NotificationChannel {
    static final String CODE = "EMAIL";

    private static final Logger LOG = LoggerFactory.getLogger(EmailNotificationChannel.class);

    private final JavaMailSender mailSender;
    private final NotificationTemplates templates;
    private final String from;

    EmailNotificationChannel(
            JavaMailSender mailSender,
            NotificationTemplates templates,
            NotificationProperties properties) {
        this.mailSender = mailSender;
        this.templates = templates;
        this.from = properties.smtp().from();
    }

    @Override
    public String channelCode() {
        return CODE;
    }

    @Override
    public NotificationDeliveryResult send(NotificationDispatch dispatch) {
        if (dispatch.userEmail() == null || dispatch.userEmail().isBlank()) {
            // Nothing to send to: trying again cannot help, and no other recipient is affected.
            LOG.warn(
                    "User {} has no email address, notification {} cannot be sent",
                    dispatch.userId(),
                    dispatch.notificationId());
            return NotificationDeliveryResult.REJECTED;
        }
        try {
            var content = templates.render(dispatch.type(), CODE, dispatch.templateContext());
            var message = mailSender.createMimeMessage();
            var helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(dispatch.userEmail());
            helper.setSubject(content.title());
            helper.setText(content.text(), content.html());
            mailSender.send(message);
            return NotificationDeliveryResult.SENT;
        } catch (AddressException e) {
            // A malformed address will not become valid on retry; it concerns this recipient only.
            LOG.warn("User {} has a malformed email address: {}", dispatch.userId(), e.toString());
            return NotificationDeliveryResult.REJECTED;
        } catch (MailException | MessagingException e) {
            var result = SmtpFailures.classify(e);
            LOG.warn(
                    "Email for notification {} not sent ({}): {}",
                    dispatch.notificationId(),
                    result,
                    e.toString());
            return result;
        }
    }
}
