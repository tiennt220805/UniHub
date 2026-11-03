package com.unihub.shared.infrastructure.out.messaging;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aPaidWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aPublishedWorkshop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.unihub.checkin.CheckInITSupport;
import com.unihub.payment.application.port.out.ConfirmOutcome;
import com.unihub.payment.application.port.out.PaymentGateway;
import com.unihub.registration.RegistrationFixtures;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.infrastructure.AuditBroker;
import com.unihub.shared.security.TestJwts;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * M-55, the business half of INV-14 (Phase 6 checklist D68, D72, 2.7; resilience-testing.md §2.7):
 * an audit pipeline that is down or hung never blocks or fails the operation it would have
 * recorded, the loss is counted and visible in a real scrape of the management port, and the
 * pipeline picks up again when the broker does. The alert half, a real Prometheus and Grafana
 * taking the counter to {@code Firing}, is {@code AuditAlertIT} (6.6).
 *
 * <p>Three broker conditions, none of them the singleton the other classes use: <b>paused</b> (the
 * process and its sockets stay, nothing is answered; a hung broker, the case that makes a naive
 * publisher hang its caller), <b>stopped</b> (connection refused), and a <b>consumer that fails</b>
 * (a message that ends in the parking lot). The four operations are the REQ-2.11.4 categories:
 * registration, payment, check-in sync and a workshop reschedule. "Does not block" is a time bound
 * on the HTTP response, not the absence of an error: a request that waited out the publish timeout
 * would pass an error check and fail this one.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
class AuditOutageIT extends CheckInITSupport {
    private static final Duration RESPONSE_BOUND = Duration.ofSeconds(2);
    private static final Duration PATIENCE = Duration.ofSeconds(60);
    private static final int BROKER_PORT = freePort();

    /** On a fixed host port, so that stopping and starting it again does not move it. */
    private static final RabbitMQContainer BROKER = fixedPortBroker();

    private static RabbitMQContainer fixedPortBroker() {
        var broker = new RabbitMQContainer("rabbitmq:3.13.7-management");
        broker.setPortBindings(List.of(BROKER_PORT + ":5672"));
        broker.start();
        return broker;
    }

    private static int freePort() {
        return com.unihub.shared.infrastructure.FixedPorts.free();
    }

    @DynamicPropertySource
    static void outageProperties(DynamicPropertyRegistry registry) {
        AuditBroker.register(registry, BROKER);
    }

    @LocalManagementPort private int managementPort;
    @Autowired private RabbitTemplate rabbit;
    @Autowired private AmqpAdmin amqpAdmin;

    @MockBean private PaymentGateway gateway;

    private RegistrationFixtures registrations;

    @BeforeEach
    void setUp() {
        recoverBroker();
        // The application's own connection was cut by the last test's stop or pause and is
        // rebuilt on first use, which can meet a broker that is not listening yet.
        await().atMost(PATIENCE)
                .ignoreExceptions()
                .until(
                        () -> {
                            AuditBroker.purgeAll(rabbit, amqpAdmin);
                            return true;
                        });
        registrations = new RegistrationFixtures(jdbc);
        reset(gateway);
        when(gateway.create(any(), any(), any(), any()))
                .thenAnswer(call -> "pi_" + UUID.randomUUID());
        when(gateway.confirm(any(), any())).thenReturn(new ConfirmOutcome.Processing());
    }

    @AfterEach
    void cleanUp() {
        recoverBroker();
        registrations.cleanUp();
    }

    // ---------- the broker ----------

    private static void freeze() {
        DockerClientFactory.lazyClient().pauseContainerCmd(BROKER.getContainerId()).exec();
    }

    private static void stop() {
        DockerClientFactory.lazyClient().stopContainerCmd(BROKER.getContainerId()).exec();
    }

    /** Whatever state the last test left it in: running, unpaused, answering. */
    private static void recoverBroker() {
        var docker = DockerClientFactory.lazyClient();
        try {
            docker.unpauseContainerCmd(BROKER.getContainerId()).exec();
        } catch (RuntimeException notPaused) {
            // running or stopped
        }
        try {
            docker.startContainerCmd(BROKER.getContainerId()).exec();
        } catch (RuntimeException alreadyRunning) {
            // fine
        }
        await().atMost(PATIENCE)
                .ignoreExceptions()
                .until(
                        () ->
                                BROKER.execInContainer("rabbitmq-diagnostics", "-q", "ping")
                                                .getExitCode()
                                        == 0);
    }

    // ---------- a scrape of the management port ----------

    private String scrape() {
        return http.getForObject(
                "http://localhost:" + managementPort + "/actuator/prometheus", String.class);
    }

    /** The counter of one reason in a real scrape; {@code -1} when the series is not there. */
    private double failures(String reason) {
        var matcher =
                Pattern.compile(
                                "^unihub_audit_publish_failures_total\\{[^}]*reason=\""
                                        + reason
                                        + "\"[^}]*} ([0-9.eE+-]+)",
                                Pattern.MULTILINE)
                        .matcher(scrape());
        return matcher.find() ? Double.parseDouble(matcher.group(1)) : -1;
    }

    private double allFailures() {
        double total = 0;
        for (var reason : List.of("dropped", "nack", "timeout", "error")) {
            total += Math.max(0, failures(reason));
        }
        return total;
    }

    // ---------- the four operations ----------

    private record Prepared(
            UserId freeStudent,
            WorkshopId freeWorkshop,
            UserId heldStudent,
            UUID heldRegistration,
            com.unihub.checkin.CheckInFixtures.Ticket ticket,
            WorkshopId toReschedule,
            UserId organizer) {}

    private ResponseEntity<String> exchange(
            HttpMethod method, String path, String bearer, UUID key, String body) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(bearer);
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (key != null) {
            headers.add("Idempotency-Key", key.toString());
        }
        return http.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private WorkshopId saved(com.unihub.workshop.domain.model.Workshop workshop) {
        tx.executeWithoutResult(s -> workshops.save(workshop));
        return registrations.track(workshop.id());
    }

    private ResponseEntity<String> registerFor(UserId student, WorkshopId workshop) {
        return exchange(
                HttpMethod.POST,
                "/api/v1/registrations",
                TestJwts.valid(student.value(), "REGISTRATION_CREATE"),
                UUID.randomUUID(),
                "{\"workshopId\":\"" + workshop.value() + "\"}");
    }

    /** Everything the four operations need, made while the broker is still healthy. */
    private Prepared prepare() throws Exception {
        var paid = saved(aPaidWorkshop().withCapacity(5).build());
        var heldStudent = registrations.aStudent();
        var held = registerFor(heldStudent, paid);
        assertThat(held.getStatusCode().value()).as(held.getBody()).isEqualTo(201);
        return new Prepared(
                registrations.aStudent(),
                saved(aPublishedWorkshop().withCapacity(5).build()),
                heldStudent,
                UUID.fromString(JSON.readTree(held.getBody()).get("id").asText()),
                fixtures.aConfirmedTicket(fixtures.aWorkshop()),
                saved(aPublishedWorkshop().build()),
                registrations.aStudent());
    }

    /** One timed call: it succeeded, and how long the caller waited. */
    private record Timed(String what, int status, Duration waited) {}

    private Timed timed(String what, java.util.function.Supplier<ResponseEntity<String>> call) {
        var start = System.nanoTime();
        var response = call.get();
        return new Timed(
                what,
                response.getStatusCode().value(),
                Duration.ofNanos(System.nanoTime() - start));
    }

    /** The four REQ-2.11.4 categories, each answered by the real endpoint. */
    private List<Timed> runTheFourOperations(Prepared p) {
        var results = new ArrayList<Timed>();
        results.add(timed("registration", () -> registerFor(p.freeStudent(), p.freeWorkshop())));
        results.add(
                timed(
                        "payment",
                        () ->
                                exchange(
                                        HttpMethod.POST,
                                        "/api/v1/payments/charge",
                                        TestJwts.valid(
                                                p.heldStudent().value(), "REGISTRATION_CREATE"),
                                        UUID.randomUUID(),
                                        "{\"registrationId\":\"%s\",\"paymentMethodToken\":\"pm_card_visa\"}"
                                                .formatted(p.heldRegistration()))));
        results.add(timed("check-in sync", () -> sync(staff, verified(p.ticket(), T0, "dev-1"))));
        results.add(
                timed(
                        "reschedule",
                        () ->
                                exchange(
                                        HttpMethod.PATCH,
                                        "/api/v1/workshops/" + p.toReschedule().value(),
                                        TestJwts.valid(p.organizer().value(), "WORKSHOP_EDIT"),
                                        null,
                                        "{\"startTime\":\"2026-12-02T09:00:00Z\","
                                                + "\"endTime\":\"2026-12-02T11:00:00Z\"}")));
        return results;
    }

    private static void assertNeitherBlockedNorFailed(List<Timed> results) {
        assertThat(results)
                .as("every operation answered 2xx")
                .allSatisfy(r -> assertThat(r.status()).as(r.what()).isBetween(200, 299));
        assertThat(results)
                .as(
                        "and none of them waited for the audit pipeline (bound: "
                                + RESPONSE_BOUND
                                + ")")
                .allSatisfy(r -> assertThat(r.waited()).as(r.what()).isLessThan(RESPONSE_BOUND));
    }

    private int auditRowsBy(UserId actor) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM audit_events WHERE actor_user_id = ?",
                Integer.class,
                actor.value());
    }

    /** The first audited operation of a test, to have the audit connection up before the outage. */
    private void warmUp() {
        var student = registrations.aStudent();
        var workshop = saved(aPublishedWorkshop().withCapacity(5).build());
        var response = registerFor(student, workshop);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        await().atMost(PATIENCE).until(() -> auditRowsBy(student) == 1);
    }

    /**
     * After the broker is back, new operations are audited again. The first entry or two may still
     * meet a broker that answers its health check but has not opened its AMQP port, or a connection
     * the client has not rebuilt yet, and are lost and counted like any other; so operations are
     * repeated until one of them reaches {@code audit_events}.
     */
    private void awaitTheAuditResumes() {
        await().atMost(PATIENCE)
                .pollInterval(Duration.ofMillis(500))
                .until(
                        () -> {
                            var student = registrations.aStudent();
                            var workshop = saved(aPublishedWorkshop().withCapacity(5).build());
                            assertThat(registerFor(student, workshop).getStatusCode().value())
                                    .isEqualTo(201);
                            try {
                                await().atMost(Duration.ofSeconds(4))
                                        .until(() -> auditRowsBy(student) == 1);
                                return true;
                            } catch (org.awaitility.core.ConditionTimeoutException notYet) {
                                return false;
                            }
                        });
    }

    // ---------- the tests ----------

    @Test
    @DisplayName(
            "a paused (hung) broker: the four operations answer 2xx inside the bound, the loss"
                    + " is counted and in a real scrape, and the pipeline resumes")
    void pausedBroker() throws Exception {
        var prepared = prepare();
        warmUp();
        var before = allFailures();
        assertThat(failures("timeout"))
                .as("the series is in the scrape at 0 before anything fails")
                .isZero();

        freeze();
        var results = runTheFourOperations(prepared);

        assertNeitherBlockedNorFailed(results);
        await().atMost(PATIENCE)
                .untilAsserted(
                        () -> {
                            assertThat(allFailures() - before)
                                    .as("5 entries were published into the silence and counted")
                                    .isGreaterThanOrEqualTo(5);
                            assertThat(failures("timeout"))
                                    .as("a broker that does not confirm is a timeout")
                                    .isGreaterThanOrEqualTo(1);
                        });

        recoverBroker();
        awaitTheAuditResumes();
    }

    @Test
    @DisplayName(
            "a stopped broker: the four operations answer 2xx inside the bound, the loss is"
                    + " counted as errors, and the pipeline resumes when the broker is back")
    void stoppedBroker() throws Exception {
        var prepared = prepare();
        warmUp();
        var before = allFailures();

        stop();
        var results = runTheFourOperations(prepared);

        assertNeitherBlockedNorFailed(results);
        await().atMost(PATIENCE)
                .untilAsserted(
                        () -> {
                            assertThat(allFailures() - before).isGreaterThanOrEqualTo(5);
                            assertThat(failures("error")).isGreaterThanOrEqualTo(1);
                        });

        recoverBroker();
        awaitTheAuditResumes();
    }

    @Test
    @DisplayName(
            "a broker that is down before the first audit entry of the process is no different:"
                    + " nothing waits, nothing fails")
    void brokerDownBeforeAnyAuditConnection() throws Exception {
        var prepared = prepare();
        freeze();

        var results = runTheFourOperations(prepared);

        assertNeitherBlockedNorFailed(results);
        await().atMost(PATIENCE).until(() -> allFailures() >= 5);
    }

    @Test
    @DisplayName(
            "a consumer that fails: the message walks the retry chain to the parking lot, and"
                    + " the business operations are untouched")
    void failingConsumer() throws Exception {
        var prepared = prepare();
        var ghost = UUID.randomUUID(); // an actor with no users row: the foreign key refuses it
        var properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setMessageId(UUID.randomUUID().toString());
        var body =
                """
                {"schemaVersion":1,"actorType":"USER","actorUserId":"%s","action":"WORKSHOP_CANCELLED",
                 "targetType":"WORKSHOP","targetId":"%s","occurredAt":"%s","metadata":{}}"""
                        .formatted(ghost, UUID.randomUUID(), Instant.now());
        rabbit.send(
                Topology.EXCHANGE,
                Topology.AUDIT_CREATED,
                MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
                        .andProperties(properties)
                        .build());

        var results = runTheFourOperations(prepared);

        assertNeitherBlockedNorFailed(results);
        await().atMost(PATIENCE)
                .alias("three retries of 0.3 s, then the parking lot, which is what alerts")
                .until(() -> parkingLotDepth() == 1);
        await().atMost(PATIENCE)
                .alias("the operations' own entries were consumed meanwhile")
                .until(() -> auditRowsBy(prepared.freeStudent()) == 1);
    }

    private int parkingLotDepth() {
        var info = amqpAdmin.getQueueInfo(Topology.PARKING_LOT);
        return info == null ? 0 : info.getMessageCount();
    }
}
