package com.unihub.notification.application.command;

import java.util.UUID;

/**
 * One delivery of a lifecycle message. {@code sourceEventId} is the message's {@code message-id},
 * the outbox row id, which keys the dedup (Phase 4 checklist D31).
 */
public record DispatchNotificationsCommand(UUID sourceEventId, NotificationFact fact) {}
