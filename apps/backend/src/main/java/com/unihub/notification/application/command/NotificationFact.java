package com.unihub.notification.application.command;

import java.time.Instant;
import java.util.UUID;

/**
 * The lifecycle fact a message announces, as the listener read it from the wire. A sealed type so
 * the listener never names a domain enum: the kind of notification is the variant.
 */
public sealed interface NotificationFact {

    /** A registration was confirmed; one recipient. */
    record RegistrationConfirmed(UUID registrationId, UUID workshopId, UUID userId)
            implements NotificationFact {}

    /** A workshop was cancelled; every student holding a seat is told. */
    record WorkshopCancelled(UUID workshopId) implements NotificationFact {}

    /** A workshop moved; every student holding a seat is told, unless the event is stale. */
    record WorkshopRescheduled(
            UUID workshopId,
            Instant previousStartTime,
            Instant previousEndTime,
            Instant newStartTime,
            Instant newEndTime)
            implements NotificationFact {}
}
