package com.unihub.shared.infrastructure.routing;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aPublishedWorkshop;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.registration.RegistrationFixtures;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.infrastructure.ReplicaIntegrationTestSupport;
import com.unihub.shared.security.TestJwts;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import com.unihub.workshop.domain.model.Workshop;
import com.unihub.workshop.infrastructure.out.persistence.WorkshopRows;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M-22 (INV-03, architecture.md §4.1, resilience-testing.md §2.5) end to end, with the Replica's
 * WAL replay paused. A caught-up Replica cannot show a misrouted read; a paused one makes it
 * deterministic. Every scenario starts with the Replica caught up and then pauses, so the data a
 * test depends on is on both nodes and only what the test writes under the pause is not.
 *
 * <p>Each consistency-sensitive scenario carries a control — a plain read-only query that really
 * does not see the write — so that a pass cannot come from a Replica that happened to be current.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MasterRoutingIT extends ReplicaIntegrationTestSupport {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String WORKSHOPS = "/api/v1/workshops";
    private static final String REGISTRATIONS = "/api/v1/registrations";
    private static final String CREATE_BODY =
            """
            {"title":"Intro to Kubernetes","description":"Hands-on.","speakerName":"Ada Lovelace",
             "location":"Room 101","startTime":"2026-12-01T09:00:00Z",
             "endTime":"2026-12-01T11:00:00Z","capacity":50,"isPaid":false}""";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add(
                "unihub.datasource.replica.url", ReplicaIntegrationTestSupport::replicaJdbcUrl);
        registry.add("unihub.security.jwt.secret", () -> TestJwts.SECRET);
        // The workshop cache is what a student read fills; the organizer scenarios need it live.
        registry.add(
                "unihub.redis.address",
                () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        registry.add("unihub.jobs.idempotency-cleanup.enabled", () -> "false");
    }

    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private WorkshopRepository workshops;
    @Autowired private RedissonClient redis;

    private final UUID organizer = UUID.randomUUID();
    private final List<UUID> createdWorkshops = new ArrayList<>();
    private RegistrationFixtures fixtures;

    @BeforeEach
    void setUp() {
        // The default HttpURLConnection-based factory cannot send PATCH.
        http.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        fixtures = new RegistrationFixtures(jdbc);
        redis.getKeys().flushdb();
    }

    @AfterEach
    void cleanUp() {
        fixtures.cleanUp();
        createdWorkshops.forEach(id -> jdbc.update("DELETE FROM workshops WHERE id = ?", id));
        redis.getKeys().flushdb();
    }

    // ---------- an organizer's view right after their own write ----------

    @Test
    @DisplayName(
            "an organizer reads a workshop right after creating it, while the Replica is behind")
    void organizer_readsAWorkshopRightAfterCreatingIt() throws Exception {
        awaitReplicaCaughtUp();

        withReplayPaused(
                () -> {
                    var created = send(HttpMethod.POST, WORKSHOPS, creator(), CREATE_BODY);
                    assertThat(created.getStatusCode().value()).isEqualTo(201);
                    var id = UUID.fromString(JSON.readTree(created.getBody()).get("id").asText());
                    createdWorkshops.add(id);
                    assertThat(rowsOnReplica("workshops", id))
                            .as("control: the Replica has not seen the new workshop")
                            .isZero();

                    var view = send(HttpMethod.GET, WORKSHOPS + "/" + id, creator(), null);

                    assertThat(view.getStatusCode().value())
                            .as("the organizer's view of their own write")
                            .isEqualTo(200);
                    assertThat(JSON.readTree(view.getBody()).get("title").asText())
                            .isEqualTo("Intro to Kubernetes");
                });
    }

    @Test
    @DisplayName(
            "an organizer sees their own edit even after a student read cached the old row from the Replica")
    void organizer_seesTheirEdit_whateverTheCacheHolds() throws Exception {
        var id = published(aPublishedWorkshop().withTitle("Before").build());
        awaitReplicaCaughtUp();

        withReplayPaused(
                () -> {
                    var edit =
                            send(
                                    HttpMethod.PATCH,
                                    WORKSHOPS + "/" + id,
                                    editor(),
                                    "{\"title\":\"After\"}");
                    assertThat(edit.getStatusCode().value()).isEqualTo(200);
                    assertThat(titleOnReplica(id))
                            .as("control: the Replica still holds the old row")
                            .isEqualTo("Before");

                    // The edit dropped the cache entry; a student's miss refills it from the
                    // lagging Replica — the known limit of workshop.md §2, not asserted here.
                    assertThat(
                                    send(HttpMethod.GET, WORKSHOPS + "/" + id, null, null)
                                            .getStatusCode()
                                            .value())
                            .isEqualTo(200);

                    var view = send(HttpMethod.GET, WORKSHOPS + "/" + id, editor(), null);

                    assertThat(JSON.readTree(view.getBody()).get("title").asText())
                            .as("the organizer's view of their own edit")
                            .isEqualTo("After");
                });
    }

    // ---------- an idempotent retry and a student's confirmation view ----------

    @Test
    @DisplayName("a retried registration replays the original response; one row, one seat")
    void retriedRegistration_replaysTheOriginal() throws Exception {
        var student = fixtures.aStudent();
        var id = published(aPublishedWorkshop().withCapacity(5).build());
        var key = UUID.randomUUID();
        awaitReplicaCaughtUp();

        withReplayPaused(
                () -> {
                    var first = register(student, id, key);
                    assertThat(first.getStatusCode().value()).isEqualTo(201);
                    assertThat(rowsOnReplica("idempotency_keys", student.value(), "actor_user_id"))
                            .as("control: the Replica has not seen the key")
                            .isZero();

                    var retry = register(student, id, key);

                    assertThat(retry.getStatusCode().value()).isEqualTo(201);
                    assertThat(JSON.readTree(retry.getBody()))
                            .as("the replay is the original Result, not a second registration")
                            .isEqualTo(JSON.readTree(first.getBody()));
                    assertThat(masterCount("registrations", "workshop_id", id)).isEqualTo(1);
                    assertThat(fixtures.seatsTaken(WorkshopId.of(id))).isEqualTo(1);
                });
    }

    @Test
    @DisplayName(
            "a student reads their registration right after making it, while the Replica is behind")
    void student_readsTheirRegistrationRightAfterMakingIt() throws Exception {
        var student = fixtures.aStudent();
        var id = published(aPublishedWorkshop().withCapacity(5).build());
        awaitReplicaCaughtUp();

        withReplayPaused(
                () -> {
                    var made = register(student, id, UUID.randomUUID());
                    assertThat(made.getStatusCode().value()).isEqualTo(201);
                    var registration = JSON.readTree(made.getBody()).get("id").asText();
                    assertThat(rowsOnReplica("registrations", UUID.fromString(registration)))
                            .as("control: the Replica has not seen the registration")
                            .isZero();

                    var one = get(REGISTRATIONS + "/" + registration, student);
                    var list = get(REGISTRATIONS, student);

                    assertThat(one.getStatusCode().value()).isEqualTo(200);
                    assertThat(JSON.readTree(one.getBody()).get("status").asText())
                            .isEqualTo("CONFIRMED");
                    assertThat(list.getStatusCode().value()).isEqualTo(200);
                    assertThat(JSON.readTree(list.getBody()).get("items").size()).isEqualTo(1);
                });
    }

    // ---------- the decision is never made from a Replica read ----------

    @Test
    @DisplayName("concurrent registrations respect capacity although the Replica shows stale seats")
    void capacityHolds_whileTheReplicaIsStale() throws Exception {
        int capacity = 3;
        var id = published(aPublishedWorkshop().withCapacity(capacity).build());
        var students = new ArrayList<UserId>();
        for (int i = 0; i < 8; i++) {
            students.add(fixtures.aStudent());
        }
        awaitReplicaCaughtUp();

        withReplayPaused(
                () -> {
                    var pool = Executors.newFixedThreadPool(students.size());
                    try {
                        List<Callable<ResponseEntity<String>>> attempts = new ArrayList<>();
                        for (var student : students) {
                            attempts.add(() -> register(student, id, UUID.randomUUID()));
                        }
                        int created = 0;
                        int rejected = 0;
                        for (Future<ResponseEntity<String>> outcome : pool.invokeAll(attempts)) {
                            int status = outcome.get().getStatusCode().value();
                            if (status == 201) {
                                created++;
                            } else if (status == 409) {
                                rejected++;
                            }
                        }
                        assertThat(created).isEqualTo(capacity);
                        assertThat(rejected).isEqualTo(students.size() - capacity);
                    } finally {
                        pool.shutdownNow();
                    }
                    assertThat(masterCount("registrations", "workshop_id", id)).isEqualTo(capacity);
                    assertThat(fixtures.seatsTaken(WorkshopId.of(id))).isEqualTo(capacity);
                    assertThat(seatsTakenOnReplica(id))
                            .as("control: the Replica still shows the pre-registration count")
                            .isZero();
                });
    }

    // ---------- routine reads go to the Replica, and converge ----------

    @Test
    @DisplayName("a plain read-only query reads the Replica: it misses the write, then converges")
    void plainReadOnlyQuery_readsTheReplica_andConverges() throws Exception {
        awaitReplicaCaughtUp();
        var workshop = aPublishedWorkshop().build();
        var id = workshop.id().value();
        createdWorkshops.add(id);

        withReplayPaused(
                () -> {
                    WorkshopRows.insert(jdbc, workshop);

                    assertThat(masterCount("workshops", "id", id)).isEqualTo(1);
                    assertThat(rowsOnReplica("workshops", id))
                            .as("a routine read-only read is served by the lagging Replica")
                            .isZero();
                });

        awaitReplicaCaughtUp();
        assertThat(rowsOnReplica("workshops", id)).isEqualTo(1);
    }

    // ---------- helpers ----------

    private UUID published(Workshop workshop) {
        new TransactionTemplate(transactions).executeWithoutResult(s -> workshops.save(workshop));
        createdWorkshops.add(workshop.id().value());
        fixtures.track(workshop.id());
        return workshop.id().value();
    }

    private ResponseEntity<String> register(UserId student, UUID workshop, UUID key) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(TestJwts.valid(student.value(), "REGISTRATION_CREATE"));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("Idempotency-Key", key.toString());
        return http.exchange(
                REGISTRATIONS,
                HttpMethod.POST,
                new HttpEntity<>("{\"workshopId\":\"" + workshop + "\"}", headers),
                String.class);
    }

    private ResponseEntity<String> get(String path, UserId student) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(TestJwts.valid(student.value(), "REGISTRATION_CREATE"));
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> send(HttpMethod method, String path, String token, String body) {
        var headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private String creator() {
        return TestJwts.valid(organizer, "WORKSHOP_CREATE");
    }

    private String editor() {
        return TestJwts.valid(organizer, "WORKSHOP_EDIT");
    }

    private TransactionTemplate readOnly() {
        var template = new TransactionTemplate(transactions);
        template.setReadOnly(true);
        return template;
    }

    /**
     * Rows a plain read-only transaction sees — the routing sends that transaction to the Replica.
     */
    private int rowsOnReplica(String table, UUID id) {
        return rowsOnReplica(table, id, "id");
    }

    private int rowsOnReplica(String table, UUID id, String column) {
        return readOnly()
                .execute(
                        s ->
                                jdbc.queryForObject(
                                        "SELECT count(*) FROM "
                                                + table
                                                + " WHERE "
                                                + column
                                                + " = ?",
                                        Integer.class,
                                        id));
    }

    private String titleOnReplica(UUID id) {
        return readOnly()
                .execute(
                        s ->
                                jdbc.queryForObject(
                                        "SELECT title FROM workshops WHERE id = ?",
                                        String.class,
                                        id));
    }

    private int seatsTakenOnReplica(UUID id) {
        return readOnly()
                .execute(
                        s ->
                                jdbc.queryForObject(
                                        "SELECT seats_taken FROM workshops WHERE id = ?",
                                        Integer.class,
                                        id));
    }

    private int masterCount(String table, String column, UUID id) {
        return new TransactionTemplate(transactions)
                .execute(
                        s ->
                                jdbc.queryForObject(
                                        "SELECT count(*) FROM "
                                                + table
                                                + " WHERE "
                                                + column
                                                + " = ?",
                                        Integer.class,
                                        id));
    }
}
