package com.unihub.notification.infrastructure.out.channel;

import com.unihub.notification.application.port.out.NotificationDeliveryResult;
import jakarta.mail.MessagingException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.springframework.mail.MailSendException;

/**
 * Maps what {@code JavaMailSender} throws to a {@link NotificationDeliveryResult} (notification.md
 * §2, Phase 4 checklist D32, D36).
 *
 * <ul>
 *   <li>A reply code <em>tied to one recipient</em> ({@code RCPT TO}, {@link
 *       SMTPAddressFailedException}): 5xx → {@code REJECTED}, 4xx → {@code DEFERRED}.
 *   <li>Everything else is a failure of the channel itself and hits every recipient alike:
 *       connection refused, timeouts, authentication and relay refusals (535 is a 5xx but not the
 *       recipient's fault) → {@code UNAVAILABLE}. Filing an authentication failure as {@code
 *       REJECTED} would let one wrong password mark every email {@code FAILED} for good.
 * </ul>
 *
 * The recipient's exception is rarely the one thrown: Spring wraps it in a {@link
 * MailSendException}, and Jakarta Mail chains the per-address failures through {@code
 * getNextException()}, so the whole graph is searched.
 */
final class SmtpFailures {

    private SmtpFailures() {}

    static NotificationDeliveryResult classify(Throwable failure) {
        var addressFailure = findAddressFailure(failure);
        if (addressFailure == null) {
            return NotificationDeliveryResult.UNAVAILABLE;
        }
        var code = addressFailure.getReturnCode();
        if (code >= 500 && code < 600) {
            return NotificationDeliveryResult.REJECTED;
        }
        if (code >= 400 && code < 500) {
            return NotificationDeliveryResult.DEFERRED;
        }
        return NotificationDeliveryResult.UNAVAILABLE;
    }

    private static SMTPAddressFailedException findAddressFailure(Throwable root) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var queue = new ArrayDeque<Throwable>();
        queue.add(root);
        while (!queue.isEmpty()) {
            var current = queue.poll();
            if (!seen.add(current)) {
                continue;
            }
            if (current instanceof SMTPAddressFailedException address) {
                return address;
            }
            addIfPresent(queue, current.getCause());
            if (current instanceof MessagingException messaging) {
                addIfPresent(queue, messaging.getNextException());
            }
            if (current instanceof MailSendException send) {
                for (var failed : send.getMessageExceptions()) {
                    addIfPresent(queue, failed);
                }
            }
        }
        return null;
    }

    /** {@code ArrayDeque} refuses {@code null}, and most links of a cause chain end in one. */
    private static void addIfPresent(ArrayDeque<Throwable> queue, Throwable next) {
        if (next != null) {
            queue.add(next);
        }
    }
}
