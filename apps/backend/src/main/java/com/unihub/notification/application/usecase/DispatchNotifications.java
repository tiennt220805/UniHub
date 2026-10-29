package com.unihub.notification.application.usecase;

import com.unihub.notification.application.command.DispatchNotificationsCommand;
import com.unihub.notification.application.command.NotificationFact;
import com.unihub.notification.application.port.in.DispatchNotificationsUseCase;
import com.unihub.notification.application.port.out.NewNotification;
import com.unihub.notification.application.port.out.NotificationDeliveryResult;
import com.unihub.notification.application.port.out.NotificationDispatch;
import com.unihub.notification.application.port.out.NotificationMetrics;
import com.unihub.notification.application.port.out.NotificationTemplates;
import com.unihub.notification.application.port.out.PendingNotification;
import com.unihub.notification.application.port.out.Recipient;
import com.unihub.notification.application.port.out.RecipientDirectory;
import com.unihub.notification.application.port.out.WorkshopDetails;
import com.unihub.notification.application.port.out.WorkshopFacts;
import com.unihub.notification.application.port.out.WorkshopRecipients;
import com.unihub.notification.domain.exception.NotificationsStillPendingException;
import com.unihub.notification.domain.model.NotificationPolicy;
import com.unihub.notification.domain.model.NotificationType;
import com.unihub.shared.messaging.NonRetryableMessageException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * One delivery of a lifecycle message (notification.md §3, Phase 4 checklist D31-D35, D39, D40).
 * Deliberately not {@code @Transactional}: a provider is never called inside a transaction
 * (backend-coding-standards.md §19.1), so each database step is its own short transaction in {@link
 * NotificationRows}.
 *
 * <ol>
 *   <li>Resolve who is told what, from the owning modules' current state.
 *   <li>Transaction 1: insert every row {@code PENDING}, {@code ON CONFLICT DO NOTHING}.
 *   <li>For each row still {@code PENDING}: claim it, send outside any transaction, then mark it.
 *   <li>If any row of the event is still {@code PENDING}, throw so the message is retried; it is
 *       never acked with a row waiting.
 * </ol>
 *
 * All per-delivery state is local: the bean is shared by the listener's threads.
 */
@Service
class DispatchNotifications implements DispatchNotificationsUseCase {
    private static final Logger LOG = LoggerFactory.getLogger(DispatchNotifications.class);

    private static final String ENTITY_WORKSHOP = "WORKSHOP";
    private static final String ENTITY_REGISTRATION = "REGISTRATION";
    private static final String IN_APP = "IN_APP";

    private final RecipientDirectory recipients;
    private final WorkshopDetails workshops;
    private final WorkshopRecipients registrations;
    private final NotificationTemplates templates;
    private final NotificationDispatcher dispatcher;
    private final NotificationRows rows;
    private final NotificationPolicy policy;
    private final NotificationMetrics metrics;

    DispatchNotifications(
            RecipientDirectory recipients,
            WorkshopDetails workshops,
            WorkshopRecipients registrations,
            NotificationTemplates templates,
            NotificationDispatcher dispatcher,
            NotificationRows rows,
            NotificationPolicy policy,
            NotificationMetrics metrics) {
        this.recipients = recipients;
        this.workshops = workshops;
        this.registrations = registrations;
        this.templates = templates;
        this.dispatcher = dispatcher;
        this.rows = rows;
        this.policy = policy;
        this.metrics = metrics;
    }

    /** What one message asks for, resolved against current state. */
    private record Plan(
            NotificationType type,
            String relatedEntityType,
            UUID relatedEntityId,
            Map<String, Object> context,
            List<UUID> recipientIds) {}

    @Override
    public void dispatch(DispatchNotificationsCommand command) {
        var eventId = command.sourceEventId();
        var plan = planFor(eventId, command.fact());
        if (plan == null) {
            return;
        }
        insertRows(eventId, plan);
        deliver(eventId, plan);
        var left = rows.countPending(eventId);
        if (left > 0) {
            throw new NotificationsStillPendingException(
                    left + " notification(s) of event " + eventId + " are still PENDING");
        }
    }

    // ---------- 1. who is told what ----------

    /** The plan, or null when the message is a no-op (a superseded reschedule). */
    private Plan planFor(UUID eventId, NotificationFact fact) {
        return switch (fact) {
            case NotificationFact.RegistrationConfirmed confirmed -> planConfirmation(confirmed);
            case NotificationFact.WorkshopCancelled cancelled -> planCancellation(cancelled);
            case NotificationFact.WorkshopRescheduled rescheduled ->
                    planReschedule(eventId, rescheduled);
        };
    }

    private Plan planConfirmation(NotificationFact.RegistrationConfirmed fact) {
        var registration =
                registrations
                        .find(fact.registrationId())
                        .orElseThrow(
                                () ->
                                        new NonRetryableMessageException(
                                                "registration "
                                                        + fact.registrationId()
                                                        + " does not exist"));
        if (!registration.userId().equals(fact.userId())) {
            throw new NonRetryableMessageException(
                    "registration " + fact.registrationId() + " belongs to another user");
        }
        recipients
                .find(fact.userId())
                .orElseThrow(
                        () ->
                                new NonRetryableMessageException(
                                        "user " + fact.userId() + " does not exist"));
        var workshop = workshopOrFail(fact.workshopId());
        var context = workshopContext(workshop);
        context.put("registrationRef", fact.registrationId().toString());
        return new Plan(
                NotificationType.REGISTRATION_CONFIRMED,
                ENTITY_REGISTRATION,
                fact.registrationId(),
                context,
                List.of(fact.userId()));
    }

    private Plan planCancellation(NotificationFact.WorkshopCancelled fact) {
        var workshop = workshopOrFail(fact.workshopId());
        return new Plan(
                NotificationType.WORKSHOP_CANCELLED,
                ENTITY_WORKSHOP,
                workshop.workshopId(),
                workshopContext(workshop),
                studentsHoldingSeats(workshop.workshopId()));
    }

    private Plan planReschedule(UUID eventId, NotificationFact.WorkshopRescheduled fact) {
        var workshop = workshopOrFail(fact.workshopId());
        if (workshop.cancelled() || !sameTimes(workshop, fact)) {
            // Messages carry no order: a newer reschedule (or a cancellation) has its own event.
            // Rows an earlier delivery of this stale event already inserted would otherwise stay
            // PENDING for ever.
            var superseded = rows.failPending(eventId);
            LOG.info(
                    "Reschedule event {} of workshop {} is superseded (cancelled={}), skipped;"
                            + " {} pending row(s) marked FAILED",
                    eventId,
                    fact.workshopId(),
                    workshop.cancelled(),
                    superseded);
            return null;
        }
        var context = workshopContext(workshop);
        context.put("previousStartTime", fact.previousStartTime());
        context.put("previousEndTime", fact.previousEndTime());
        return new Plan(
                NotificationType.WORKSHOP_RESCHEDULED,
                ENTITY_WORKSHOP,
                workshop.workshopId(),
                context,
                studentsHoldingSeats(workshop.workshopId()));
    }

    private WorkshopFacts workshopOrFail(UUID workshopId) {
        return workshops
                .find(workshopId)
                .orElseThrow(
                        () ->
                                new NonRetryableMessageException(
                                        "workshop " + workshopId + " does not exist"));
    }

    private List<UUID> studentsHoldingSeats(UUID workshopId) {
        return registrations.holdingSeats(workshopId).stream()
                .map(registration -> registration.userId())
                .distinct()
                .toList();
    }

    /** Whether the event's new times are still the workshop's current ones (to the microsecond). */
    private static boolean sameTimes(
            WorkshopFacts workshop, NotificationFact.WorkshopRescheduled fact) {
        return micros(workshop.startTime()).equals(micros(fact.newStartTime()))
                && micros(workshop.endTime()).equals(micros(fact.newEndTime()));
    }

    private static Instant micros(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS);
    }

    private static Map<String, Object> workshopContext(WorkshopFacts workshop) {
        var context = new HashMap<String, Object>();
        context.put("workshopTitle", workshop.title());
        context.put("startTime", workshop.startTime());
        context.put("endTime", workshop.endTime());
        context.put("location", workshop.location());
        return context;
    }

    // ---------- 2. transaction 1: the rows ----------

    private void insertRows(UUID eventId, Plan plan) {
        var contacts = recipients.findAll(plan.recipientIds());
        var toInsert = new ArrayList<NewNotification>();
        for (var userId : plan.recipientIds()) {
            var contact = contacts.get(userId);
            if (contact == null) {
                // One unresolvable recipient must not stop everyone else learning of a
                // cancellation (D35).
                LOG.warn("Recipient {} of event {} cannot be resolved, skipped", userId, eventId);
                continue;
            }
            for (var channel : policy.channelsOf(plan.type())) {
                String title = null;
                String body = null;
                if (IN_APP.equals(channel)) {
                    var rendered =
                            templates.render(plan.type(), channel, contextFor(plan, contact));
                    title = rendered.title();
                    body = rendered.text();
                }
                toInsert.add(
                        new NewNotification(
                                eventId,
                                userId,
                                channel,
                                plan.type().name(),
                                plan.relatedEntityType(),
                                plan.relatedEntityId(),
                                title,
                                body));
            }
        }
        var inserted = rows.insertIfAbsent(toInsert);
        LOG.info(
                "Event {} ({}): {} row(s) inserted, {} already there",
                eventId,
                plan.type(),
                inserted,
                toInsert.size() - inserted);
    }

    private static Map<String, Object> contextFor(Plan plan, Recipient contact) {
        var context = new HashMap<>(plan.context());
        context.put("recipientName", contact.fullName());
        return context;
    }

    // ---------- 3. claim, send, mark ----------

    private void deliver(UUID eventId, Plan plan) {
        var pending = rows.findPending(eventId);
        if (pending.isEmpty()) {
            return;
        }
        var contacts =
                recipients.findAll(
                        pending.stream().map(PendingNotification::userId).distinct().toList());
        var unavailableChannels = new HashSet<String>();
        for (var row : pending) {
            if (unavailableChannels.contains(row.channel())) {
                continue;
            }
            var contact = contacts.get(row.userId());
            if (contact == null) {
                LOG.warn(
                        "Recipient {} of notification {} no longer resolves, marking it FAILED",
                        row.userId(),
                        row.id());
                rows.markFailed(row.id());
                continue;
            }
            var token = rows.claim(row.id(), policy.claimLease());
            if (token.isEmpty()) {
                // Another delivery holds it or has finished it. If it is still PENDING at the end
                // the message is retried rather than acked.
                continue;
            }
            var result = send(row, plan, contact);
            metrics.delivered(row.channel(), result);
            switch (result) {
                case SENT -> rows.markSent(row.id());
                case REJECTED -> rows.markFailed(row.id());
                case DEFERRED -> rows.release(row.id(), token.get());
                case UNAVAILABLE -> {
                    rows.release(row.id(), token.get());
                    unavailableChannels.add(row.channel());
                }
            }
        }
    }

    /** A channel that throws is {@code UNAVAILABLE} for itself and never cuts the others short. */
    private NotificationDeliveryResult send(PendingNotification row, Plan plan, Recipient contact) {
        var dispatch =
                new NotificationDispatch(
                        row.id(),
                        row.userId(),
                        contact.email(),
                        plan.type(),
                        contextFor(plan, contact));
        try {
            return dispatcher.dispatch(row.channel(), dispatch);
        } catch (RuntimeException e) {
            LOG.error(
                    "Channel {} threw while sending notification {}: {}",
                    row.channel(),
                    row.id(),
                    e.toString(),
                    e);
            metrics.channelThrew(row.channel());
            return NotificationDeliveryResult.UNAVAILABLE;
        }
    }
}
