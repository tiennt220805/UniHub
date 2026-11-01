package com.unihub.workshop.brochure;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aDraftWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aPaidWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aPublishedWorkshop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.Volume;
import com.unihub.checkin.CheckInFixtures;
import com.unihub.payment.application.port.out.ConfirmOutcome;
import com.unihub.payment.application.port.out.PaymentGateway;
import com.unihub.registration.RegistrationFixtures;
import com.unihub.shared.infrastructure.out.messaging.Topology;
import com.unihub.shared.security.TestJwts;
import com.unihub.workshop.web.dto.Workshop;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * M-51, INV-19, AC60.1 (Phase 5 checklist 9.6; workshop.md §10; resilience-testing.md §2.6): an
 * object-storage outage is a brochure-flow problem and nothing else. The bucket is a MinIO of this
 * class's own (the singleton serves every other class), taken down two ways: {@code stop} (the
 * connection is refused) and {@code pause} (it accepts and never answers, the one that hangs a
 * client without a timeout). In each:
 *
 * <ul>
 *   <li>{@code confirmBrochureUpload} answers {@code 503 storage-unavailable} <b>within a time
 *       bound</b>, with {@code Retry-After}, and persists nothing: no {@code brochure_s3_key}, no
 *       outbox row, the pending key kept;
 *   <li>a summarization request that arrives while the bucket is down walks the retry chain to the
 *       parking lot, the AI stand-in is never called and {@code ai_summary} stays {@code NULL};
 *   <li>issuing an upload URL still works (it is computed locally; checked, not assumed);
 *   <li>browsing, a free and a paid registration, a charge, a check-in sync and the publication of
 *       a workshop answer {@code 2xx}, each within a bound, for the whole window;
 *   <li>once the bucket is back, the very same confirmation succeeds and the summary arrives.
 * </ul>
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ObjectStorageOutageIT extends BrochureHttpSupport {
    private static final Duration BOUND = Duration.ofSeconds(20);
    private static final Duration PROBE_BOUND = Duration.ofSeconds(5);
    private static final String VOLUME = "unihub-it-minio-" + UUID.randomUUID();

    /** The bucket server and the fixed host port it was started on. */
    private record Bucket(MinIOContainer container, int port) {}

    // Its own bucket server on a fixed host port, so that a stopped and restarted container is
    // found
    // at the same address by a client that was configured once. A port that was free a moment ago
    // can be taken by another container before this one starts (seen in the full suite), so the
    // start is tried again on a new port.
    private static final Bucket BUCKET = startBucket();
    private static final MinIOContainer OWN = BUCKET.container();
    private static final int MINIO_PORT = BUCKET.port();

    private static Bucket startBucket() {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            var port = freePort();
            var container =
                    new MinIOContainer(
                                    DockerImageName.parse(
                                                    "cgr.dev/chainguard/minio@sha256:"
                                                            + "4692462f35d97d7e82c30371d82f057703c5d9489bcae726010594c812f2d285")
                                            .asCompatibleSubstituteFor("minio/minio"))
                            .withCreateContainerCmdModifier(
                                    cmd ->
                                            cmd.getHostConfig()
                                                    // A named volume, not the container's overlay
                                                    // layer: after a stop and a start MinIO renames
                                                    // inside /data, and on the overlay that fails
                                                    // with "Rename across devices".
                                                    .withBinds(
                                                            new Bind(VOLUME, new Volume("/data")))
                                                    .withPortBindings(
                                                            new PortBinding(
                                                                    Ports.Binding.bindPort(port),
                                                                    new ExposedPort(9000)),
                                                            // the console port stays an ordinary
                                                            // random one
                                                            new PortBinding(
                                                                    Ports.Binding.empty(),
                                                                    new ExposedPort(9001))));
            try {
                container.start();
                return new Bucket(container, port);
            } catch (RuntimeException e) {
                last = e;
                container.stop();
            }
        }
        throw new IllegalStateException("could not start a MinIO on a free port", last);
    }

    private static int freePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("unihub.object-storage.endpoint", () -> "http://localhost:" + MINIO_PORT);
        registry.add("unihub.object-storage.access-key", OWN::getUserName);
        registry.add("unihub.object-storage.secret-key", OWN::getPassword);
        registry.add("unihub.object-storage.connect-timeout", () -> "PT1S");
        registry.add("unihub.object-storage.read-timeout", () -> "PT2S");
        registry.add("unihub.jobs.outbox-relay.enabled", () -> "true");
        registry.add("unihub.jobs.outbox-relay.interval", () -> "PT0.2S");
        // The paid registration of the probes leaves a hold job behind; a short hold lets it run
        // its
        // course (a no-op: the registration is confirmed) before the class ends, instead of firing
        // 15 minutes later for a registration that no longer exists, into another class's queue.
        registry.add("unihub.registration.seat-hold", () -> "PT5S");
    }

    @AfterAll
    static void stopOwn() {
        OWN.stop();
        try {
            DockerClientFactory.instance().client().removeVolumeCmd(VOLUME).exec();
        } catch (RuntimeException alreadyGone) {
            // nothing left to remove
        }
    }

    @MockBean private PaymentGateway gateway;

    private RegistrationFixtures registrations;
    private CheckInFixtures checkIn;
    private final List<UUID> students = new java.util.ArrayList<>();

    @BeforeEach
    void setUpProbes() {
        jdbc.update("DELETE FROM event_publications");
        registrations = new RegistrationFixtures(jdbc);
        checkIn = new CheckInFixtures(jdbc, workshops, tx);
        reset(gateway);
        when(gateway.create(any(), any(), any(), any()))
                .thenAnswer(call -> "pi_" + UUID.randomUUID());
        when(gateway.confirm(any(), any())).thenReturn(new ConfirmOutcome.Succeeded());
        ensureUp();
    }

    @AfterEach
    void bringTheBucketBack() {
        ensureUp();
        // The assertions of the test are over by now. What follows only has to leave the shared
        // database as it found it, whether or not the background work (notifications through the
        // live
        // relay, the hold job of the paid registration) has finished: the rows are deleted in a
        // finally, so a slow runner cannot leave workshops behind for the next class.
        try {
            await().atMost(Duration.ofSeconds(45))
                    .during(Duration.ofSeconds(1))
                    .until(
                            () ->
                                    deleteNotifications() == 0
                                            && queueDepth(Topology.HOLD_DELAY_QUEUE) == 0
                                            && queueDepth("unihub.seat-hold-expiry.queue") == 0);
        } catch (RuntimeException notQuiet) {
            System.out.println(
                    "[ObjectStorageOutageIT] cleanup did not go quiet: delayQueue="
                            + queueDepth(Topology.HOLD_DELAY_QUEUE)
                            + " expiryQueue="
                            + queueDepth("unihub.seat-hold-expiry.queue")
                            + " notificationQueue="
                            + queueDepth("unihub.notification.queue")
                            + " pendingOutbox="
                            + jdbc.queryForObject(
                                    "SELECT count(*) FROM event_publications WHERE status='PENDING'",
                                    Integer.class));
        } finally {
            deleteNotifications();
            students.clear();
            try {
                checkIn.cleanUp();
            } finally {
                registrations.cleanUp();
            }
        }
    }

    private int deleteNotifications() {
        var deleted = 0;
        for (var user : students) {
            deleted += jdbc.update("DELETE FROM notifications WHERE user_id = ?", user);
        }
        return deleted;
    }

    // ---------- the faults ----------

    private static void pause() {
        DockerClientFactory.instance().client().pauseContainerCmd(OWN.getContainerId()).exec();
    }

    private static void unpause() {
        try {
            DockerClientFactory.instance()
                    .client()
                    .unpauseContainerCmd(OWN.getContainerId())
                    .exec();
        } catch (RuntimeException alreadyRunning) {
            // not paused
        }
    }

    private static void stop() {
        DockerClientFactory.instance()
                .client()
                .stopContainerCmd(OWN.getContainerId())
                .withTimeout(2)
                .exec();
    }

    private static void start() {
        try {
            DockerClientFactory.instance().client().startContainerCmd(OWN.getContainerId()).exec();
        } catch (RuntimeException alreadyRunning) {
            // not stopped
        }
    }

    private void ensureUp() {
        unpause();
        start();
        try {
            await().atMost(Duration.ofSeconds(90))
                    .ignoreExceptions()
                    .until(
                            () -> {
                                s3.headBucket(b -> b.bucket(storage.bucket()));
                                return true;
                            });
        } catch (RuntimeException e) {
            var logs = OWN.getLogs();
            throw new IllegalStateException(
                    "the bucket did not come back: "
                            + logs.substring(Math.max(0, logs.length() - 1500)),
                    e);
        }
    }

    // ---------- the probes ----------

    private record Timed(ResponseEntity<String> response, Duration took) {}

    private Timed timed(java.util.function.Supplier<ResponseEntity<String>> request) {
        var started = System.nanoTime();
        var response = request.get();
        return new Timed(response, Duration.ofNanos(System.nanoTime() - started));
    }

    private ResponseEntity<String> bearer(
            HttpMethod method, String path, String token, String body, UUID key) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (key != null) {
            headers.add("Idempotency-Key", key.toString());
        }
        return http.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    /** Everything that is not the brochure flow, answered while the bucket is down. */
    private void everythingElseKeepsWorking(String phase) throws Exception {
        var student = registrations.aStudent();
        students.add(student.value());
        var studentToken = TestJwts.valid(student.value(), "REGISTRATION_CREATE");

        var freeWorkshop = aPublishedWorkshop().withCapacity(5).build();
        var paidWorkshop = aPaidWorkshop().withCapacity(5).build();
        var draft = aDraftWorkshop().build();
        tx.executeWithoutResult(
                s -> {
                    workshops.save(freeWorkshop);
                    workshops.save(paidWorkshop);
                    workshops.save(draft);
                });
        for (var w : List.of(freeWorkshop, paidWorkshop, draft)) {
            registrations.track(w.id());
        }
        var venue = checkIn.aWorkshop();
        var staff = checkIn.aStaffMember();
        var ticket = checkIn.aConfirmedTicket(venue);
        var scan =
                new com.unihub.checkin.CheckInITSupport.Scan(
                        UUID.randomUUID(),
                        "VERIFIED",
                        "\"" + ticket.code() + "\"",
                        venue.value(),
                        Instant.now(),
                        "outage-device");

        var browse = timed(() -> http.getForEntity("/api/v1/workshops", String.class));
        var detail =
                timed(
                        () ->
                                http.getForEntity(
                                        "/api/v1/workshops/" + freeWorkshop.id().value(),
                                        String.class));
        var free =
                timed(
                        () ->
                                bearer(
                                        HttpMethod.POST,
                                        "/api/v1/registrations",
                                        studentToken,
                                        "{\"workshopId\":\"" + freeWorkshop.id().value() + "\"}",
                                        UUID.randomUUID()));
        var paidStudent = registrations.aStudent();
        students.add(paidStudent.value());
        var paidToken = TestJwts.valid(paidStudent.value(), "REGISTRATION_CREATE");
        var reserve =
                timed(
                        () ->
                                bearer(
                                        HttpMethod.POST,
                                        "/api/v1/registrations",
                                        paidToken,
                                        "{\"workshopId\":\"" + paidWorkshop.id().value() + "\"}",
                                        UUID.randomUUID()));
        assertThat(reserve.response().getStatusCode())
                .as("reserve, " + phase)
                .isEqualTo(HttpStatus.CREATED);
        var registrationId = JSON.readTree(reserve.response().getBody()).get("id").asText();
        var charge =
                timed(
                        () ->
                                bearer(
                                        HttpMethod.POST,
                                        "/api/v1/payments/charge",
                                        paidToken,
                                        "{\"registrationId\":\""
                                                + registrationId
                                                + "\",\"paymentMethodToken\":\"pm_card_visa\"}",
                                        UUID.randomUUID()));
        var sync =
                timed(
                        () ->
                                bearer(
                                        HttpMethod.POST,
                                        "/api/v1/checkin/sync",
                                        TestJwts.valid(staff.value(), "CHECKIN_SCAN"),
                                        "{\"scans\":[" + scan.json() + "]}",
                                        null));
        var publish =
                timed(
                        () ->
                                bearer(
                                        HttpMethod.POST,
                                        "/api/v1/workshops/" + draft.id().value() + "/publish",
                                        organizer(),
                                        null,
                                        null));

        var probes =
                java.util.Map.of(
                        "catalog",
                        browse,
                        "detail",
                        detail,
                        "free registration",
                        free,
                        "paid charge",
                        charge,
                        "check-in sync",
                        sync,
                        "publish a workshop",
                        publish);
        probes.forEach(
                (name, probe) -> {
                    assertThat(probe.response().getStatusCode().is2xxSuccessful())
                            .as("%s, %s: %s", name, phase, probe.response().getBody())
                            .isTrue();
                    assertThat(probe.took())
                            .as("%s, %s: latency", name, phase)
                            .isLessThan(PROBE_BOUND);
                });
    }

    // ---------- the scenario, once per fault ----------

    private void outage(String fault, Runnable down, Runnable up) throws Exception {
        // Before: one upload fully issued and uploaded, waiting for its confirmation; and a request
        // for a brochure that is already on the row, to arrive while the bucket is down.
        var waiting = aWorkshop();
        var issued = issue(waiting, organizer());
        assertThat(issued.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(put(uploadUrl(issued), A_PDF)).isEqualTo(200);
        var pending = pendingKeyOf(waiting);
        var parkedBefore = queueDepth(PARKING_LOT);
        var other = aConfirmedBrochure(A_PDF);
        aiSummarizes("after the outage");

        down.run();
        try {
            // The confirmation: 503, in time, with Retry-After, and nothing persisted.
            var confirmation = timed(() -> confirm(waiting, organizer()));
            assertThat(confirmation.response().getStatusCode())
                    .as("confirm during %s", fault)
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(confirmation.took())
                    .as("a hang cannot pass as success: %s", fault)
                    .isLessThan(BOUND);
            assertThat(json(confirmation.response()).get("type").asText())
                    .isEqualTo("urn:unihub:problem:storage-unavailable");
            assertThat(confirmation.response().getHeaders().getFirst("Retry-After")).isNotNull();
            assertThat(brochureKeyOf(waiting)).as("no brochure_s3_key (AC60.1)").isNull();
            assertThat(pendingKeyOf(waiting)).as("the pending key is kept").isEqualTo(pending);
            assertThat(summarizationRequests(waiting)).isZero();

            // Issuing a URL is local: it works with the bucket down.
            var reissued = timed(() -> issue(aWorkshop(), organizer()));
            assertThat(reissued.response().getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(reissued.took()).isLessThan(PROBE_BOUND);

            // A summarization request meets the dead bucket: it walks the retry chain to the
            // parking lot, never reaches the AI service, and the summary stays empty.
            publishRequested(other);
            await().atMost(Duration.ofSeconds(60))
                    .untilAsserted(
                            () -> assertThat(queueDepth(PARKING_LOT)).isEqualTo(parkedBefore + 1));
            assertThat(aiCalls()).isZero();
            assertThat(summaryOf(other.workshop())).isNull();

            // And the rest of the system does not notice (INV-19).
            everythingElseKeepsWorking("during " + fault);
        } finally {
            up.run();
        }
        ensureUp();

        // After: the very same confirmation succeeds, and the summary arrives.
        var warmed = http.getForObject("/api/v1/workshops/" + waiting, Workshop.class);
        assertThat(warmed.hasBrochure()).isFalse();
        var recovered = confirm(waiting, organizer());
        assertThat(recovered.getStatusCode()).as("after %s", fault).isEqualTo(HttpStatus.OK);
        assertThat(brochureKeyOf(waiting)).isEqualTo(pending);
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () ->
                                assertThat(
                                                http.getForObject(
                                                                "/api/v1/workshops/" + waiting,
                                                                Workshop.class)
                                                        .aiSummary())
                                        .isEqualTo("after the outage"));
        everythingElseKeepsWorking("after " + fault);
    }

    @Test
    @DisplayName(
            "M-51, AC60.1: with the bucket stopped (connection refused), only the brochure flow is affected")
    void stopped_isolatesTheBrochureFlow() throws Exception {
        outage("stop", ObjectStorageOutageIT::stop, ObjectStorageOutageIT::start);
    }

    @Test
    @DisplayName(
            "M-51, AC60.1: with the bucket paused (accepts, never answers), confirm still answers 503 in time and nothing else is slowed")
    void paused_isolatesTheBrochureFlow() throws Exception {
        outage("pause", ObjectStorageOutageIT::pause, ObjectStorageOutageIT::unpause);
    }
}
