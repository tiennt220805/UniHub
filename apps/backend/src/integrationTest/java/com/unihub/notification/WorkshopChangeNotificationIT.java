package com.unihub.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.infrastructure.Mailpit;
import com.unihub.shared.security.TestJwts;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * M-38 end to end (Phase 4 checklist 4.1, 4.2): an organizer cancels or reschedules a workshop over
 * real HTTP, and nothing else; the outbox row committed with the change goes through the real relay
 * and the real consumer, and every student who holds a seat is told by email and in-app. Recipients
 * are decided when the message is consumed, from the registrations of that moment (D35): {@code
 * RESERVED} and {@code CONFIRMED}, never {@code EXPIRED} or {@code CANCELLED}.
 *
 * <p>Each test waits until every row it expects is {@code SENT} and only then counts, so "exactly
 * one" is a statement about a settled system. The relay is on in this context, so it is closed when
 * the class ends.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkshopChangeNotificationIT extends NotificationITSupport {

    private static final Duration LONG_ENOUGH = Duration.ofSeconds(40);

    @DynamicPropertySource
    static void relayProperties(DynamicPropertyRegistry registry) {
        registry.add("unihub.jobs.outbox-relay.enabled", () -> "true");
        registry.add("unihub.jobs.outbox-relay.interval", () -> "PT0.2S");
    }

    @Autowired private TestRestTemplate http;

    private final List<UUID> workshopIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // The default HttpURLConnection-based factory cannot send PATCH.
        http.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        // the live relay would publish whatever an earlier class left committed
        jdbc.update("DELETE FROM event_publications");
        workshopIds.clear();
    }

    /** Before the superclass removes the workshops these rows point at. */
    @AfterEach
    void forgetTheWorkshopEvents() {
        workshopIds.forEach(
                id -> jdbc.update("DELETE FROM event_publications WHERE aggregate_id = ?", id));
    }

    /** A published workshop whose title carries a token no other test shares. */
    private WorkshopId workshop(String token) {
        var id = aWorkshop("Change " + token + " workshop");
        workshopIds.add(id.value());
        return id;
    }

    private void call(HttpMethod method, String path, String body) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(TestJwts.valid(UUID.randomUUID(), "WORKSHOP_EDIT"));
        headers.setContentType(MediaType.APPLICATION_JSON);
        var response = http.exchange(path, method, new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode().value()).as(path).isEqualTo(200);
    }

    private void awaitBothChannelsSent(int perChannel, UUID... students) {
        Awaitility.await()
                .atMost(LONG_ENOUGH)
                .untilAsserted(
                        () -> {
                            for (var student : students) {
                                assertThat(notificationRows(student, "EMAIL", "SENT"))
                                        .as("email of " + student)
                                        .isEqualTo(perChannel);
                                assertThat(notificationRows(student, "IN_APP", "SENT"))
                                        .as("in-app of " + student)
                                        .isEqualTo(perChannel);
                            }
                        });
    }

    @Test
    @DisplayName(
            "AC35.1: cancelling tells exactly the students who hold a seat, once each, and the"
                    + " organizer does nothing else")
    void cancel_notifiesEveryoneHoldingASeat() {
        var token = "cancel" + UUID.randomUUID().toString().substring(0, 8);
        var workshop = workshop(token);
        var confirmed = List.of(student(), student(), student());
        var reserved = student();
        var expired = student();
        var cancelled = student();
        var bystander = student();
        confirmed.forEach(s -> registration(s, workshop, "CONFIRMED"));
        registration(reserved, workshop, "RESERVED");
        registration(expired, workshop, "EXPIRED");
        registration(cancelled, workshop, "CANCELLED");
        registration(bystander, aWorkshop("Elsewhere " + token), "CONFIRMED");
        var recipients = new ArrayList<>(confirmed);
        recipients.add(reserved);

        // the organizer's one and only action
        call(HttpMethod.POST, "/api/v1/workshops/" + workshop.value() + "/cancel", null);

        awaitBothChannelsSent(1, recipients.toArray(UUID[]::new));
        assertThat(Mailpit.recipientsOfSubject(token))
                .as("one email for each of the four who hold a seat, and nobody else")
                .containsExactlyInAnyOrderElementsOf(
                        recipients.stream().map(this::emailOf).toList());
        for (var left : List.of(expired, cancelled, bystander)) {
            assertThat(notificationRows(left)).as("rows of " + left).isZero();
        }
        var mail = Mailpit.to(emailOf(reserved)).getFirst();
        assertThat(mail.subject()).startsWith("Workshop đã bị hủy: ").contains(token);
        assertThat(mail.text()).contains("đã bị hủy");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM notifications WHERE type ="
                                        + " 'WORKSHOP_CANCELLED' AND related_entity_id = ?",
                                Integer.class,
                                workshop.value()))
                .as("N recipients x 2 channels")
                .isEqualTo(8);
        assertThat(queuedMessages()).isZero();
        assertThat(parkedMessages()).isZero();
    }

    @Test
    @DisplayName(
            "rescheduling twice notifies twice with the old and new times, an edit that keeps the"
                    + " times notifies nobody (AC31.3)")
    void reschedule_notifiesOncePerChange() {
        var token = "move" + UUID.randomUUID().toString().substring(0, 8);
        var workshop = workshop(token);
        var ann = student();
        var bob = student();
        registration(ann, workshop, "CONFIRMED");
        registration(bob, workshop, "RESERVED");
        var path = "/api/v1/workshops/" + workshop.value();

        // an edit that moves nothing: nobody hears of it
        call(HttpMethod.PATCH, path, "{\"title\":\"Change " + token + " renamed\"}");
        call(
                HttpMethod.PATCH,
                path,
                "{\"startTime\":\"2026-12-02T09:00:00Z\",\"endTime\":\"2026-12-02T11:00:00Z\"}");
        // every row of the first change is done before the second one is made: a message whose new
        // times are no longer the workshop's is superseded and notifies nobody (AC35.5)
        awaitBothChannelsSent(1, ann, bob);
        call(
                HttpMethod.PATCH,
                path,
                "{\"startTime\":\"2026-12-03T09:00:00Z\",\"endTime\":\"2026-12-03T11:00:00Z\"}");
        awaitBothChannelsSent(2, ann, bob);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM event_publications WHERE aggregate_id = ?"
                                        + " AND routing_key = 'unihub.workshop.event.rescheduled'",
                                Integer.class,
                                workshop.value()))
                .as("the title-only edit wrote no event")
                .isEqualTo(2);
        for (var student : List.of(ann, bob)) {
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(DISTINCT source_event_id) FROM notifications"
                                            + " WHERE user_id = ?",
                                    Integer.class,
                                    student))
                    .as("two events, two notifications each")
                    .isEqualTo(2);
            var mails = Mailpit.to(emailOf(student));
            assertThat(mails).hasSize(2).allSatisfy(m -> assertThat(m.subject()).contains(token));
            assertThat(mails)
                    .extracting(Mailpit.Mail::text)
                    .anySatisfy(
                            text ->
                                    assertThat(text)
                                            .contains(
                                                    "Thời gian cũ: 01/12/2026 16:00 - 01/12/2026"
                                                            + " 18:00")
                                            .contains(
                                                    "Thời gian mới: 02/12/2026 16:00 - 02/12/2026"
                                                            + " 18:00"))
                    .anySatisfy(
                            text ->
                                    assertThat(text)
                                            .contains(
                                                    "Thời gian cũ: 02/12/2026 16:00 - 02/12/2026"
                                                            + " 18:00")
                                            .contains(
                                                    "Thời gian mới: 03/12/2026 16:00 - 03/12/2026"
                                                            + " 18:00"));
        }
        assertThat(queuedMessages()).isZero();
        assertThat(parkedMessages()).isZero();
    }
}
