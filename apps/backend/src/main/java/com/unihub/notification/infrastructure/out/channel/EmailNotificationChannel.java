package com.unihub.notification.infrastructure.out.channel;

import com.unihub.notification.application.port.out.NotificationChannel;
import com.unihub.notification.application.port.out.NotificationDeliveryResult;
import com.unihub.notification.application.port.out.NotificationDispatch;
import com.unihub.notification.application.port.out.NotificationTemplates;
import com.unihub.notification.application.port.out.TicketQrImageException;
import com.unihub.notification.application.port.out.TicketQrImages;
import com.unihub.notification.infrastructure.config.NotificationProperties;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.AddressException;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Email over SMTP (notification.md §2, Phase 4 checklist D36): plain text plus a minimal HTML
 * alternative, vendor-neutral. Never throws for a delivery failure; {@link SmtpFailures} turns
 * whatever the mail library threw into a result.
 *
 * <p>A confirmation whose context carries the QR payload of a ticket gets the QR drawn as a PNG and
 * attached inline ({@code Content-ID} {@value NotificationTemplates#TICKET_QR_CONTENT_ID}), which
 * the HTML refers to as {@code cid:} (Phase 5 checklist D55). A payload that cannot be drawn fails
 * the same way every time, so the email goes without the image and a warning is logged, rather than
 * walking the retry chain into the parking lot.
 */
@Component
class EmailNotificationChannel implements NotificationChannel {
    static final String CODE = "EMAIL";

    private static final Logger LOG = LoggerFactory.getLogger(EmailNotificationChannel.class);

    private final JavaMailSender mailSender;
    private final NotificationTemplates templates;
    private final TicketQrImages qrImages;
    private final String from;

    EmailNotificationChannel(
            JavaMailSender mailSender,
            NotificationTemplates templates,
            TicketQrImages qrImages,
            NotificationProperties properties) {
        this.mailSender = mailSender;
        this.templates = templates;
        this.qrImages = qrImages;
        this.from = properties.smtp().from();
    }

    @Override
    public String channelCode() {
        return CODE;
    }

    /**
     * The PNG of the ticket in the context, or null when there is no ticket or it cannot be drawn.
     */
    private byte[] drawTicket(NotificationDispatch dispatch, Map<String, Object> context) {
        if (!(context.get(NotificationTemplates.TICKET_QR_PAYLOAD) instanceof String payload)
                || payload.isBlank()) {
            return null;
        }
        try {
            return qrImages.pngOf(payload);
        } catch (TicketQrImageException e) {
            // Retrying cannot help, and the student should still be told. The payload is the
            // ticket: it is not in the message.
            LOG.warn(
                    "The e-ticket QR of notification {} cannot be drawn, sending the email without"
                            + " it: {}",
                    dispatch.notificationId(),
                    e.getMessage());
            return null;
        }
    }

    private static Map<String, Object> withoutTicket(Map<String, Object> context) {
        var copy = new HashMap<>(context);
        copy.remove(NotificationTemplates.TICKET_QR_PAYLOAD);
        return copy;
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
            var context = dispatch.templateContext();
            var qr = drawTicket(dispatch, context);
            if (qr == null) {
                // no image: the template must not refer to one either
                context = withoutTicket(context);
            }
            var content = templates.render(dispatch.type(), CODE, context);
            var message = mailSender.createMimeMessage();
            var helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(dispatch.userEmail());
            helper.setSubject(content.title());
            helper.setText(content.text(), content.html());
            if (qr != null) {
                helper.addInline(
                        NotificationTemplates.TICKET_QR_CONTENT_ID,
                        new ByteArrayResource(qr),
                        "image/png");
            }
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
