package com.unihub.notification.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unihub.notification.application.command.DispatchNotificationsCommand;
import com.unihub.notification.application.command.NotificationFact;
import com.unihub.notification.application.port.out.NotificationChannel;
import com.unihub.notification.application.port.out.NotificationDeliveryResult;
import com.unihub.notification.application.port.out.NotificationDispatch;
import com.unihub.notification.application.port.out.NotificationMetrics;
import com.unihub.notification.application.port.out.NotificationTemplates;
import com.unihub.notification.application.port.out.Recipient;
import com.unihub.notification.application.port.out.RecipientDirectory;
import com.unihub.notification.application.port.out.RegistrationRef;
import com.unihub.notification.application.port.out.RenderedNotification;
import com.unihub.notification.application.port.out.WorkshopDetails;
import com.unihub.notification.application.port.out.WorkshopFacts;
import com.unihub.notification.application.port.out.WorkshopRecipients;
import com.unihub.notification.domain.exception.NotificationsStillPendingException;
import com.unihub.notification.domain.model.NotificationPolicy;
import com.unihub.notification.domain.model.NotificationType;
import com.unihub.shared.messaging.NonRetryableMessageException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The delivery logic of notification.md §3 and §5 against in-memory fakes: which rows exist, which
 * are sent, what a retry does. The database semantics it relies on (the unique key, the conditional
 * updates, the lease) are proved against Postgres in {@code JdbcNotificationRepositoryIT}.
 */
class DispatchNotificationsTest {

    private static final Instant START = Instant.parse("2026-12-01T02:00:00Z");
    private static final Instant END = Instant.parse("2026-12-01T04:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(120);

    /** A clock the test moves, to age a claim past its lease. */
    static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-11-01T00:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** A channel whose answer per recipient the test scripts; remembers every dispatch. */
    static final class ScriptedChannel implements NotificationChannel {
        private final String code;
        final List<NotificationDispatch> sent = new ArrayList<>();
        Function<NotificationDispatch, NotificationDeliveryResult> script =
                d -> NotificationDeliveryResult.SENT;
        RuntimeException throwing;

        ScriptedChannel(String code) {
            this.code = code;
        }

        @Override
        public String channelCode() {
            return code;
        }

        @Override
        public NotificationDeliveryResult send(NotificationDispatch dispatch) {
            sent.add(dispatch);
            if (throwing != null) {
                throw throwing;
            }
            return script.apply(dispatch);
        }

        long attemptsFor(UUID userId) {
            return sent.stream().filter(d -> d.userId().equals(userId)).count();
        }
    }

    static final class RecordingMetrics implements NotificationMetrics {
        final Map<String, Integer> counts = new HashMap<>();

        @Override
        public void delivered(String channel, NotificationDeliveryResult result) {
            counts.merge(channel + ":" + result, 1, Integer::sum);
        }

        @Override
        public void channelThrew(String channel) {
            counts.merge(channel + ":THREW", 1, Integer::sum);
        }
    }

    private final MovableClock clock = new MovableClock();
    private final InMemoryNotificationRepository repository =
            new InMemoryNotificationRepository(clock);
    private final ScriptedChannel email = new ScriptedChannel("EMAIL");
    private final ScriptedChannel inApp = new ScriptedChannel("IN_APP");
    private final RecordingMetrics metrics = new RecordingMetrics();

    private final Map<UUID, Recipient> users = new LinkedHashMap<>();
    private final Map<UUID, WorkshopFacts> workshopsById = new HashMap<>();
    private final Map<UUID, List<RegistrationRef>> holders = new HashMap<>();
    private final Map<UUID, RegistrationRef> registrationsById = new HashMap<>();

    private DispatchNotifications useCase;
    private RecipientDirectory directory;

    @BeforeEach
    void setUp() {
        directory =
                new RecipientDirectory() {
                    @Override
                    public Optional<Recipient> find(UUID userId) {
                        return Optional.ofNullable(users.get(userId));
                    }

                    @Override
                    public Map<UUID, Recipient> findAll(Collection<UUID> userIds) {
                        var found = new LinkedHashMap<UUID, Recipient>();
                        userIds.forEach(
                                id ->
                                        Optional.ofNullable(users.get(id))
                                                .ifPresent(r -> found.put(id, r)));
                        return found;
                    }
                };
        WorkshopDetails workshopDetails = id -> Optional.ofNullable(workshopsById.get(id));
        WorkshopRecipients recipients =
                new WorkshopRecipients() {
                    @Override
                    public List<RegistrationRef> holdingSeats(UUID workshopId) {
                        return holders.getOrDefault(workshopId, List.of());
                    }

                    @Override
                    public Optional<RegistrationRef> find(UUID registrationId) {
                        return Optional.ofNullable(registrationsById.get(registrationId));
                    }
                };
        NotificationTemplates templates =
                (type, channel, context) ->
                        new RenderedNotification(
                                type + "/" + channel + " title",
                                type + "/" + channel + " body for " + context.get("recipientName"),
                                null);
        var policy =
                new NotificationPolicy(
                        LEASE,
                        Map.of(
                                NotificationType.REGISTRATION_CONFIRMED, List.of("EMAIL", "IN_APP"),
                                NotificationType.WORKSHOP_CANCELLED, List.of("EMAIL", "IN_APP"),
                                NotificationType.WORKSHOP_RESCHEDULED, List.of("EMAIL", "IN_APP")));
        useCase =
                new DispatchNotifications(
                        directory,
                        workshopDetails,
                        recipients,
                        templates,
                        new NotificationDispatcher(List.of(email, inApp)),
                        new NotificationRows(repository),
                        policy,
                        metrics);
    }

    // ---------- fixtures ----------

    private UUID student(String name) {
        var id = UUID.randomUUID();
        users.put(id, new Recipient(id, name, name.toLowerCase() + "@example.test"));
        return id;
    }

    private UUID workshop(boolean cancelled) {
        var id = UUID.randomUUID();
        workshopsById.put(
                id, new WorkshopFacts(id, "Intro to K8s", START, END, "Room 101", cancelled));
        return id;
    }

    private RegistrationRef registration(UUID workshopId, UUID userId) {
        var ref = new RegistrationRef(UUID.randomUUID(), userId, workshopId);
        registrationsById.put(ref.registrationId(), ref);
        holders.computeIfAbsent(workshopId, k -> new ArrayList<>()).add(ref);
        return ref;
    }

    private static DispatchNotificationsCommand cancelled(UUID eventId, UUID workshopId) {
        return new DispatchNotificationsCommand(
                eventId, new NotificationFact.WorkshopCancelled(workshopId));
    }

    private static DispatchNotificationsCommand rescheduled(
            UUID eventId, UUID workshopId, Instant newStart, Instant newEnd) {
        return new DispatchNotificationsCommand(
                eventId,
                new NotificationFact.WorkshopRescheduled(
                        workshopId,
                        START.minusSeconds(86400),
                        END.minusSeconds(86400),
                        newStart,
                        newEnd));
    }

    private static DispatchNotificationsCommand confirmed(UUID eventId, RegistrationRef ref) {
        return new DispatchNotificationsCommand(
                eventId,
                new NotificationFact.RegistrationConfirmed(
                        ref.registrationId(), ref.workshopId(), ref.userId()));
    }

    // ---------- registration confirmed ----------

    @Test
    @DisplayName(
            "a confirmation creates an email row and an in-app row, sends both, content on the in-app row")
    void confirmation_sendsBothChannels() {
        var ann = student("Ann");
        var ws = workshop(false);
        var ref = registration(ws, ann);

        useCase.dispatch(confirmed(UUID.randomUUID(), ref));

        assertThat(repository.count("SENT")).isEqualTo(2);
        assertThat(repository.count("PENDING")).isZero();
        var inAppRow = repository.rowsOf(ann, "IN_APP").getFirst();
        assertThat(inAppRow.data.title()).isEqualTo("REGISTRATION_CONFIRMED/IN_APP title");
        assertThat(inAppRow.data.body()).contains("Ann");
        assertThat(repository.rowsOf(ann, "EMAIL").getFirst().data.title()).isNull();
        assertThat(inAppRow.data.relatedEntityType()).isEqualTo("REGISTRATION");
        assertThat(inAppRow.data.relatedEntityId()).isEqualTo(ref.registrationId());
        var mail = email.sent.getFirst();
        assertThat(mail.userEmail()).isEqualTo("ann@example.test");
        assertThat(mail.type()).isEqualTo(NotificationType.REGISTRATION_CONFIRMED);
        assertThat(mail.templateContext())
                .containsEntry("registrationRef", ref.registrationId().toString())
                .containsEntry("recipientName", "Ann")
                .containsEntry("workshopTitle", "Intro to K8s");
        assertThat(metrics.counts).containsEntry("EMAIL:SENT", 1).containsEntry("IN_APP:SENT", 1);
    }

    @Test
    @DisplayName(
            "a confirmation naming a registration, user or workshop that does not exist is permanent")
    void confirmation_withMissingData_isNonRetryable() {
        var ann = student("Ann");
        var ws = workshop(false);
        var ref = registration(ws, ann);
        var noRegistration = new RegistrationRef(UUID.randomUUID(), ann, ws);
        var noUser = registration(ws, UUID.randomUUID());
        var noWorkshop = registration(UUID.randomUUID(), ann);
        var someoneElse = new RegistrationRef(ref.registrationId(), student("Bob"), ws);

        for (var bad : List.of(noRegistration, noUser, noWorkshop, someoneElse)) {
            assertThatThrownBy(() -> useCase.dispatch(confirmed(UUID.randomUUID(), bad)))
                    .isInstanceOf(NonRetryableMessageException.class);
        }
        assertThat(repository.rows).as("no row, nothing to retry").isEmpty();
        assertThat(email.sent).isEmpty();
    }

    // ---------- cancellation fan-out ----------

    @Test
    @DisplayName("a cancellation tells every student holding a seat, once per channel")
    void cancellation_fansOutToEveryHolder() {
        var ws = workshop(true);
        var students = List.of(student("Ann"), student("Bob"), student("Cho"));
        students.forEach(s -> registration(ws, s));

        useCase.dispatch(cancelled(UUID.randomUUID(), ws));

        assertThat(repository.rows).hasSize(6);
        assertThat(repository.count("SENT")).isEqualTo(6);
        assertThat(email.sent)
                .extracting(NotificationDispatch::userId)
                .containsExactlyElementsOf(students);
        assertThat(repository.rows.getFirst().data.relatedEntityType()).isEqualTo("WORKSHOP");
        assertThat(repository.rows.getFirst().data.relatedEntityId()).isEqualTo(ws);
    }

    @Test
    @DisplayName(
            "a recipient that cannot be resolved is skipped; everyone else is still told (AC35.6)")
    void cancellation_skipsAnUnresolvableRecipient() {
        var ws = workshop(true);
        var ann = student("Ann");
        registration(ws, ann);
        registration(ws, UUID.randomUUID()); // no such user
        var cho = student("Cho");
        registration(ws, cho);

        useCase.dispatch(cancelled(UUID.randomUUID(), ws));

        assertThat(email.sent).extracting(NotificationDispatch::userId).containsExactly(ann, cho);
        assertThat(repository.rows).hasSize(4);
        assertThat(repository.count("PENDING")).isZero();
    }

    @Test
    @DisplayName("a cancellation of a workshop that does not exist is permanent")
    void cancellation_ofAMissingWorkshop_isNonRetryable() {
        assertThatThrownBy(() -> useCase.dispatch(cancelled(UUID.randomUUID(), UUID.randomUUID())))
                .isInstanceOf(NonRetryableMessageException.class);
    }

    // ---------- reschedule ----------

    @Test
    @DisplayName(
            "a reschedule matching the workshop's current times is sent with the old and new times")
    void reschedule_current_isSent() {
        var ws = workshop(false);
        var ann = student("Ann");
        registration(ws, ann);

        useCase.dispatch(rescheduled(UUID.randomUUID(), ws, START, END));

        assertThat(email.sent).hasSize(1);
        assertThat(email.sent.getFirst().templateContext())
                .containsEntry("previousStartTime", START.minusSeconds(86400))
                .containsEntry("startTime", START);
    }

    @Test
    @DisplayName("a reschedule after the workshop was cancelled is a no-op (AC35.4)")
    void reschedule_afterCancellation_isNoOp() {
        var ws = workshop(true);
        registration(ws, student("Ann"));

        useCase.dispatch(rescheduled(UUID.randomUUID(), ws, START, END));

        assertThat(repository.rows).isEmpty();
        assertThat(email.sent).isEmpty();
    }

    @Test
    @DisplayName("an older reschedule, processed after a newer one, is a no-op (AC35.5)")
    void reschedule_superseded_isNoOp() {
        var ws = workshop(false);
        registration(ws, student("Ann"));
        var stale = START.minusSeconds(3600);

        useCase.dispatch(rescheduled(UUID.randomUUID(), ws, stale, END));

        assertThat(repository.rows).isEmpty();
        assertThat(email.sent).isEmpty();
    }

    @Test
    @DisplayName(
            "a reschedule that became stale after rows were inserted leaves none PENDING (AC35.7)")
    void reschedule_supersededAfterInsert_failsItsPendingRows() {
        var ws = workshop(false);
        registration(ws, student("Ann"));
        var eventId = UUID.randomUUID();
        email.script = d -> NotificationDeliveryResult.UNAVAILABLE;
        assertThatThrownBy(() -> useCase.dispatch(rescheduled(eventId, ws, START, END)))
                .isInstanceOf(NotificationsStillPendingException.class);
        assertThat(repository.count("PENDING")).isEqualTo(1);

        // The workshop moves again before the redelivery.
        var newer = START.plusSeconds(7200);
        workshopsById.put(
                ws,
                new WorkshopFacts(
                        ws, "Intro to K8s", newer, END.plusSeconds(7200), "Room 101", false));
        useCase.dispatch(rescheduled(eventId, ws, START, END));

        assertThat(repository.count("PENDING")).isZero();
        assertThat(repository.count("FAILED")).isEqualTo(1);
    }

    @Test
    @DisplayName("two reschedules of the same workshop are two notifications (AC31.3)")
    void twoReschedules_areTwoNotifications() {
        var ws = workshop(false);
        var ann = student("Ann");
        registration(ws, ann);

        useCase.dispatch(rescheduled(UUID.randomUUID(), ws, START, END));
        useCase.dispatch(rescheduled(UUID.randomUUID(), ws, START, END));

        assertThat(repository.rowsOf(ann, "IN_APP")).hasSize(2);
        assertThat(email.sent).hasSize(2);
    }

    // ---------- delivery results ----------

    @Test
    @DisplayName(
            "REJECTED marks that row FAILED without retry; the other channel and recipients go on (AC32.1)")
    void rejected_failsTheRowOnly() {
        var ws = workshop(true);
        var ann = student("Ann");
        var bob = student("Bob");
        registration(ws, ann);
        registration(ws, bob);
        email.script =
                d ->
                        d.userId().equals(ann)
                                ? NotificationDeliveryResult.REJECTED
                                : NotificationDeliveryResult.SENT;

        useCase.dispatch(cancelled(UUID.randomUUID(), ws));

        assertThat(repository.rowsOf(ann, "EMAIL").getFirst().status).isEqualTo("FAILED");
        assertThat(repository.rowsOf(ann, "IN_APP").getFirst().status).isEqualTo("SENT");
        assertThat(repository.rowsOf(bob, "EMAIL").getFirst().status).isEqualTo("SENT");
    }

    @Test
    @DisplayName(
            "DEFERRED holds back only that recipient: the channel carries on, then the message is retried (AC32.6)")
    void deferred_continuesWithTheNextRecipient() {
        var ws = workshop(true);
        var ann = student("Ann");
        var bob = student("Bob");
        var cho = student("Cho");
        List.of(ann, bob, cho).forEach(s -> registration(ws, s));
        email.script =
                d ->
                        d.userId().equals(ann)
                                ? NotificationDeliveryResult.DEFERRED
                                : NotificationDeliveryResult.SENT;

        assertThatThrownBy(() -> useCase.dispatch(cancelled(UUID.randomUUID(), ws)))
                .isInstanceOf(NotificationsStillPendingException.class);

        assertThat(repository.rowsOf(bob, "EMAIL").getFirst().status).isEqualTo("SENT");
        assertThat(repository.rowsOf(cho, "EMAIL").getFirst().status).isEqualTo("SENT");
        var deferred = repository.rowsOf(ann, "EMAIL").getFirst();
        assertThat(deferred.status).isEqualTo("PENDING");
        assertThat(deferred.claimedAt).as("claim released so the next hop can take it").isNull();
        assertThat(repository.rowsOf(ann, "IN_APP").getFirst().status).isEqualTo("SENT");
    }

    @Test
    @DisplayName(
            "UNAVAILABLE stops only its own channel for this delivery; the rows stay PENDING (AC39.2)")
    void unavailable_skipsTheRestOfThatChannelOnly() {
        var ws = workshop(true);
        var students = List.of(student("Ann"), student("Bob"), student("Cho"));
        students.forEach(s -> registration(ws, s));
        email.script = d -> NotificationDeliveryResult.UNAVAILABLE;

        assertThatThrownBy(() -> useCase.dispatch(cancelled(UUID.randomUUID(), ws)))
                .isInstanceOf(NotificationsStillPendingException.class);

        assertThat(email.sent).as("one attempt, not one per recipient").hasSize(1);
        assertThat(inApp.sent).hasSize(3);
        assertThat(repository.count("PENDING")).isEqualTo(3);
        assertThat(repository.rows.stream().filter(r -> r.status.equals("PENDING")))
                .allSatisfy(r -> assertThat(r.claimedAt).isNull());
    }

    @Test
    @DisplayName(
            "a channel that throws is UNAVAILABLE for itself and never cuts the others short (AC40.3)")
    void throwingChannel_doesNotAffectTheOthers() {
        var ws = workshop(true);
        var ann = student("Ann");
        var bob = student("Bob");
        registration(ws, ann);
        registration(ws, bob);
        email.throwing = new IllegalStateException("provider SDK blew up");

        assertThatThrownBy(() -> useCase.dispatch(cancelled(UUID.randomUUID(), ws)))
                .isInstanceOf(NotificationsStillPendingException.class);

        assertThat(repository.rowsOf(ann, "IN_APP").getFirst().status).isEqualTo("SENT");
        assertThat(repository.rowsOf(bob, "IN_APP").getFirst().status).isEqualTo("SENT");
        assertThat(email.sent).hasSize(1);
        assertThat(metrics.counts).containsEntry("EMAIL:THREW", 1);
    }

    // ---------- redelivery ----------

    @Test
    @DisplayName(
            "a redelivery after an outage sends only what is still PENDING, once (AC32.2, AC33.2)")
    void redelivery_sendsOnlyPendingRows() {
        var ws = workshop(true);
        var ann = student("Ann");
        registration(ws, ann);
        var eventId = UUID.randomUUID();
        email.script = d -> NotificationDeliveryResult.UNAVAILABLE;
        assertThatThrownBy(() -> useCase.dispatch(cancelled(eventId, ws)))
                .isInstanceOf(NotificationsStillPendingException.class);
        email.script = d -> NotificationDeliveryResult.SENT;

        useCase.dispatch(cancelled(eventId, ws));

        assertThat(repository.rows).as("no new row").hasSize(2);
        assertThat(repository.count("SENT")).isEqualTo(2);
        assertThat(email.sent).as("one failed attempt, one good one").hasSize(2);
        assertThat(inApp.sent).as("the in-app row was not sent again").hasSize(1);
    }

    @Test
    @DisplayName(
            "the same message delivered three times in a row is one row and one email per recipient (AC31.1)")
    void sequentialRedelivery_isANoOp() {
        var ws = workshop(true);
        var ann = student("Ann");
        registration(ws, ann);
        var eventId = UUID.randomUUID();

        useCase.dispatch(cancelled(eventId, ws));
        useCase.dispatch(cancelled(eventId, ws));
        useCase.dispatch(cancelled(eventId, ws));

        assertThat(repository.rows).hasSize(2);
        assertThat(email.attemptsFor(ann)).isEqualTo(1);
        assertThat(inApp.attemptsFor(ann)).isEqualTo(1);
    }

    // ---------- the send claim ----------

    @Test
    @DisplayName(
            "a row under another delivery's live claim is not sent; the message is retried instead of acked (AC33.4)")
    void liveClaim_skipsTheRowAndRetries() {
        var ws = workshop(true);
        var ann = student("Ann");
        registration(ws, ann);
        var eventId = UUID.randomUUID();
        email.script = d -> NotificationDeliveryResult.DEFERRED;
        assertThatThrownBy(() -> useCase.dispatch(cancelled(eventId, ws)))
                .isInstanceOf(NotificationsStillPendingException.class);
        // Another pod now holds the email row.
        var held = repository.rowsOf(ann, "EMAIL").getFirst();
        var token = repository.claim(held.id, LEASE).orElseThrow();
        email.sent.clear();
        email.script = d -> NotificationDeliveryResult.SENT;

        assertThatThrownBy(() -> useCase.dispatch(cancelled(eventId, ws)))
                .isInstanceOf(NotificationsStillPendingException.class);

        assertThat(email.sent).as("not sent while the other delivery holds it").isEmpty();
        assertThat(held.claimedAt).isEqualTo(token);
    }

    @Test
    @DisplayName(
            "a claim whose holder crashed expires, and the next delivery sends the row once (AC33.3, AC33.5)")
    void expiredClaim_isTakenOver() {
        var ws = workshop(true);
        var ann = student("Ann");
        registration(ws, ann);
        var eventId = UUID.randomUUID();
        email.script = d -> NotificationDeliveryResult.DEFERRED;
        assertThatThrownBy(() -> useCase.dispatch(cancelled(eventId, ws)))
                .isInstanceOf(NotificationsStillPendingException.class);
        repository.claim(repository.rowsOf(ann, "EMAIL").getFirst().id, LEASE); // crashed holder
        clock.advance(LEASE.plusSeconds(1));
        email.sent.clear();
        email.script = d -> NotificationDeliveryResult.SENT;

        useCase.dispatch(cancelled(eventId, ws));

        assertThat(email.sent).hasSize(1);
        assertThat(repository.count("PENDING")).isZero();
    }

    // ---------- policy ----------

    @Test
    @DisplayName("the channels of a type come from the policy, not from the publisher or the code")
    void channelsComeFromThePolicy() {
        var onlyEmail =
                new NotificationPolicy(
                        LEASE,
                        Map.of(
                                NotificationType.REGISTRATION_CONFIRMED, List.of("EMAIL"),
                                NotificationType.WORKSHOP_CANCELLED, List.of("EMAIL"),
                                NotificationType.WORKSHOP_RESCHEDULED, List.of("EMAIL")));
        var narrowed =
                new DispatchNotifications(
                        directory,
                        workshopDetailsOf(),
                        holdersOf(),
                        (t, c, x) -> new RenderedNotification("t", "b", null),
                        new NotificationDispatcher(List.of(email, inApp)),
                        new NotificationRows(repository),
                        onlyEmail,
                        metrics);
        var ws = workshop(true);
        var ann = student("Ann");
        registration(ws, ann);

        narrowed.dispatch(cancelled(UUID.randomUUID(), ws));

        assertThat(repository.rows).extracting(r -> r.data.channel()).containsOnly("EMAIL");
    }

    private WorkshopDetails workshopDetailsOf() {
        return id -> Optional.ofNullable(workshopsById.get(id));
    }

    private WorkshopRecipients holdersOf() {
        return new WorkshopRecipients() {
            @Override
            public List<RegistrationRef> holdingSeats(UUID workshopId) {
                return holders.getOrDefault(workshopId, List.of());
            }

            @Override
            public Optional<RegistrationRef> find(UUID registrationId) {
                return Optional.empty();
            }
        };
    }
}
