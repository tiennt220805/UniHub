package com.unihub.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.registration.TicketITSupport;
import com.unihub.registration.api.TicketVerificationKey;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.infrastructure.Mailpit;
import com.unihub.shared.security.TestJwts;
import java.time.Duration;
import java.util.UUID;
import org.awaitility.Awaitility;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * M-37, the free branch, with nothing stood in for the system under test (Phase 4 checklist 3.1,
 * AC37.1): a student registers over real HTTP; the registration commits {@code ticket.confirmed} to
 * the outbox with the business write; the real relay publishes it; the real consumer sends the
 * email (Mailpit is the SMTP server) and stores the in-app notification; and the student's inbox
 * endpoint shows it. The test waits until both channels are done and only then counts, so "exactly
 * one" is a statement about a settled system, not about an early look.
 *
 * <p>The relay is on in this context, unlike in most, so the context is closed when the class ends.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RegistrationConfirmationNotificationIT extends NotificationITSupport {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration LONG_ENOUGH = Duration.ofSeconds(30);

    @DynamicPropertySource
    static void relayProperties(DynamicPropertyRegistry registry) {
        registry.add("unihub.jobs.outbox-relay.enabled", () -> "true");
        registry.add("unihub.jobs.outbox-relay.interval", () -> "PT0.2S");
    }

    @Autowired private TestRestTemplate http;
    @Autowired private TicketVerificationKey verificationKey;

    @BeforeEach
    void noRowsFromEarlierTests() {
        // the live relay would publish whatever an earlier class left committed
        jdbc.update("DELETE FROM event_publications");
    }

    private JsonNode register(UUID student, WorkshopId workshop) throws Exception {
        var headers = new HttpHeaders();
        headers.setBearerAuth(TestJwts.valid(student, "REGISTRATION_CREATE"));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("Idempotency-Key", UUID.randomUUID().toString());
        var response =
                http.exchange(
                        "/api/v1/registrations",
                        HttpMethod.POST,
                        new HttpEntity<>("{\"workshopId\":\"" + workshop.value() + "\"}", headers),
                        String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return JSON.readTree(response.getBody());
    }

    private JsonNode inbox(UUID student) throws Exception {
        var headers = new HttpHeaders();
        headers.setBearerAuth(TestJwts.valid(student));
        var response =
                http.exchange(
                        "/api/v1/notifications",
                        HttpMethod.GET,
                        new HttpEntity<>(headers),
                        String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return JSON.readTree(response.getBody());
    }

    @Test
    @DisplayName(
            "AC37.1: registering for a free workshop sends exactly one email and stores one in-app"
                    + " notification, and the inbox shows it")
    void freeRegistration_isConfirmedByEmailAndInApp() throws Exception {
        var student = student();
        var workshop = aWorkshop("Intro to Kubernetes <b>live</b>");

        var registration = register(student, workshop).get("id").asText();

        Awaitility.await()
                .atMost(LONG_ENOUGH)
                .untilAsserted(
                        () -> {
                            assertThat(notificationRows(student, "EMAIL", "SENT")).isEqualTo(1);
                            assertThat(notificationRows(student, "IN_APP", "SENT")).isEqualTo(1);
                        });

        // exactly one email, to the student, and nothing else is waiting anywhere
        var mails = Mailpit.to(emailOf(student));
        assertThat(mails).hasSize(1);
        var mail = mails.getFirst();
        assertThat(mail.subject()).contains("Intro to Kubernetes <b>live</b>");
        assertThat(mail.text())
                .contains("Intro to Kubernetes <b>live</b>")
                // 2026-12-01T09:00:00Z, shown in Asia/Ho_Chi_Minh
                .contains("01/12/2026 16:00")
                .contains(registration)
                .contains("http://localhost:5173/me/registrations");
        assertThat(mail.html())
                .as("the HTML part is escaped, never markup from a workshop name")
                .contains("Intro to Kubernetes &lt;b&gt;live&lt;/b&gt;")
                .doesNotContain("<b>live</b>")
                .contains(registration)
                .contains("http://localhost:5173/me/registrations");
        assertThat(notificationRows(student)).as("one row per channel").isEqualTo(2);
        awaitNoOutboxRowPending();
        assertThat(queuedMessages()).isZero();
        assertThat(parkedMessages()).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM event_publications WHERE aggregate_id = ?"
                                        + " AND routing_key = ? AND status = 'PUBLISHED'",
                                Integer.class,
                                UUID.fromString(registration),
                                CONFIRMED))
                .as("one ticket.confirmed, published by the relay")
                .isEqualTo(1);

        // the in-app row carries what the student reads
        var row =
                jdbc.queryForMap(
                        "SELECT type, related_entity_type, related_entity_id, title, body, sent_at"
                                + " FROM notifications WHERE user_id = ? AND channel = 'IN_APP'",
                        student);
        assertThat(row.get("type")).isEqualTo("REGISTRATION_CONFIRMED");
        assertThat(row.get("related_entity_type")).isEqualTo("REGISTRATION");
        assertThat(row.get("related_entity_id")).isEqualTo(UUID.fromString(registration));
        assertThat((String) row.get("title")).contains("Intro to Kubernetes <b>live</b>");
        assertThat((String) row.get("body")).isNotBlank();
        assertThat(row.get("sent_at")).isNotNull();

        // and the student finds it through the API
        var inbox = inbox(student);
        assertThat(inbox.at("/items")).hasSize(1);
        assertThat(inbox.at("/items/0/type").asText()).isEqualTo("REGISTRATION_CONFIRMED");
        assertThat(inbox.at("/items/0/status").asText()).isEqualTo("SENT");
        assertThat(inbox.at("/items/0/title").asText()).isEqualTo((String) row.get("title"));
        assertThat(inbox.at("/items/0/relatedEntityId").asText()).isEqualTo(registration);
    }

    @Test
    @DisplayName(
            "AC55.1: the confirmation email carries the e-ticket QR inline, and the QR is the stored"
                    + " ticket, which the published key verifies")
    void freeRegistration_emailCarriesTheTicketQr() throws Exception {
        var student = student();
        var workshop = aWorkshop("Intro to Kubernetes");

        var registration = UUID.fromString(register(student, workshop).get("id").asText());

        Awaitility.await()
                .atMost(LONG_ENOUGH)
                .untilAsserted(
                        () -> assertThat(notificationRows(student, "EMAIL", "SENT")).isEqualTo(1));
        var mail = Mailpit.to(emailOf(student)).getFirst();
        var stored = TicketITSupport.storedTicket(jdbc, registration);

        assertThat(mail.inline()).as("exactly one inline image").hasSize(1);
        var image = mail.inline().getFirst();
        assertThat(image.contentType()).startsWith("image/png");
        assertThat(mail.html())
                .as("the HTML refers to the very part that was attached")
                .contains("src=\"cid:" + image.contentId() + "\"");
        assertThat(TicketQrDecoder.decode(image.bytes()))
                .as("the QR is the stored payload, exactly")
                .isEqualTo(stored.qrPayload());
        TicketITSupport.assertVerifies(stored, verificationKey.current(), workshop.value());
        var ticketUrl = "http://localhost:5173/me/registrations/" + registration;
        assertThat(mail.text()).contains(ticketUrl);
        assertThat(mail.html()).contains("href=\"" + ticketUrl + "\"");

        // the ticket is a credential: it is in no stored notification, no inbox answer, no text
        assertThat(mail.text()).doesNotContain(stored.qrPayload());
        assertThat(mail.html()).doesNotContain(stored.qrPayload());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM notifications WHERE user_id = ? AND"
                                        + " (title LIKE ? OR body LIKE ?)",
                                Integer.class,
                                student,
                                "%" + stored.qrPayload() + "%",
                                "%" + stored.qrPayload() + "%"))
                .isZero();
        assertThat(inbox(student).toString()).doesNotContain(stored.qrPayload());
        assertThat(inbox(student).toString()).doesNotContain(stored.code());
    }

    @Test
    @DisplayName(
            "AC55.2: a registration confirmed before tickets existed is still confirmed, without an"
                    + " image, and nothing is retried or parked")
    void registrationWithoutATicket_isConfirmedWithoutTheImage() {
        var student = student();
        var workshop = aWorkshop("Confirmed before Phase 5");
        var registration = registration(student, workshop, "CONFIRMED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT qr_payload FROM registrations WHERE id = ?",
                                String.class,
                                registration))
                .as("fixture: a row with no ticket")
                .isNull();

        publishConfirmed(registration, workshop, student);

        Awaitility.await()
                .atMost(LONG_ENOUGH)
                .untilAsserted(
                        () -> {
                            assertThat(notificationRows(student, "EMAIL", "SENT")).isEqualTo(1);
                            assertThat(notificationRows(student, "IN_APP", "SENT")).isEqualTo(1);
                        });
        var mails = Mailpit.to(emailOf(student));
        assertThat(mails).hasSize(1);
        var mail = mails.getFirst();
        assertThat(mail.inline()).isEmpty();
        assertThat(mail.html())
                .doesNotContain("<img")
                .doesNotContain("cid:")
                .contains("http://localhost:5173/me/registrations")
                .doesNotContain("/me/registrations/" + registration);
        assertThat(mail.text()).contains(registration.toString());
        awaitNoOutboxRowPending();
        assertThat(queuedMessages()).isZero();
        assertThat(parkedMessages()).isZero();
    }

    @Test
    @DisplayName("a second student's registration notifies only that student")
    void eachStudentIsNotifiedAboutTheirOwnRegistrationOnly() throws Exception {
        var ann = student();
        var bob = student();
        var workshop = aWorkshop("Shared workshop");

        register(ann, workshop);
        register(bob, workshop);

        Awaitility.await()
                .atMost(LONG_ENOUGH)
                .untilAsserted(
                        () -> {
                            for (var student : new UUID[] {ann, bob}) {
                                assertThat(notificationRows(student, "EMAIL", "SENT")).isEqualTo(1);
                                assertThat(notificationRows(student, "IN_APP", "SENT"))
                                        .isEqualTo(1);
                            }
                        });
        assertThat(Mailpit.to(emailOf(ann))).hasSize(1);
        assertThat(Mailpit.to(emailOf(bob))).hasSize(1);
        assertThat(inbox(ann).at("/items")).hasSize(1);
        assertThat(inbox(bob).at("/items")).hasSize(1);
        assertThat(parkedMessages()).isZero();
    }
}
