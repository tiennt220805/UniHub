package com.unihub.registration.infrastructure.in.messaging;

import com.unihub.registration.application.command.ExpireSeatHoldCommand;
import com.unihub.registration.application.port.in.ExpireSeatHoldUseCase;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.messaging.NonRetryableMessageException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Registration's side of "a hold's time is up" (registration.md §3, messaging.md §2): the delay
 * queue dead-letters the {@code hold.reserved} job back as {@code hold.expired}, with the job's
 * body unchanged. The message type is this module's own reading of the wire contract. The listener
 * maps and delegates; it never catches, so a failure reaches the shared error handler, which keeps
 * a seat-lock failure in the chain for as long as it lasts (backend-coding-standards.md §8.5-§8.6,
 * checklist D19).
 */
@Component
class SeatHoldExpiryListener {
    static final String QUEUE = "unihub.seat-hold-expiry.queue";
    private static final int SUPPORTED_SCHEMA_VERSION = 1;

    /** The fields of the {@code SeatHoldExpiryRequested} job this module reads. */
    record SeatHoldExpiryMessage(
            UUID registrationId,
            UUID workshopId,
            Instant holdExpiresAt,
            Instant occurredAt,
            Integer schemaVersion) {}

    private final ExpireSeatHoldUseCase expireSeatHold;

    SeatHoldExpiryListener(ExpireSeatHoldUseCase expireSeatHold) {
        this.expireSeatHold = expireSeatHold;
    }

    @RabbitListener(queues = QUEUE)
    void onHoldExpired(SeatHoldExpiryMessage message) {
        if (message.schemaVersion() == null
                || message.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
            throw new NonRetryableMessageException(
                    "unsupported schemaVersion " + message.schemaVersion());
        }
        if (message.registrationId() == null || message.workshopId() == null) {
            throw new NonRetryableMessageException("hold.expired without its ids");
        }
        expireSeatHold.expire(
                new ExpireSeatHoldCommand(
                        RegistrationId.of(message.registrationId()),
                        WorkshopId.of(message.workshopId())));
    }
}
