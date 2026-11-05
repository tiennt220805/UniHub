package com.unihub.shared.infrastructure.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.registration.infrastructure.out.crypto.TicketTestKeys;
import com.unihub.shared.infrastructure.FixedPorts;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.security.TestJwts;
import com.unihub.workshop.infrastructure.out.persistence.WorkshopRows;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.zip.ZipFile;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;

/**
 * INV-15 (M-58; architecture.md §6.2; Phase 7 checklist 4.3, D88): a migration runs while the
 * previous release is still serving, so the schema it leaves must be readable and writable by that
 * release. This runs <b>the application of the commit before the newest migration (N-1)</b> — its
 * boot jar, built from git history by {@code scripts/build-previous-release.sh} — against a
 * database migrated to the schema of HEAD, and runs a fixed set of reads and writes over HTTP:
 * sign-in, the catalog, a free registration (twice, with one idempotency key), reading it, listing
 * one's registrations, the notification inbox.
 *
 * <ul>
 *   <li>{@link #previousReleaseServesTheSchemaOfHead}: HEAD's own boot jar runs the set first, as a
 *       control (if HEAD fails a step, the step is wrong, not N-1); then N-1 runs it on the same
 *       database.
 *   <li>{@link #rolloutWithTheOldReleaseStillRunning}: the order of a real rollout. The database is
 *       at the schema N-1 was built for, N-1 serves and writes, the newest migration is applied
 *       <em>while N-1 keeps running</em>, N-1 serves again over rows it wrote itself, and then HEAD
 *       starts and reads what N-1 wrote.
 * </ul>
 *
 * Both applications are child JVMs of their own boot jars with an empty environment of their own
 * (the suite's switches — relay, listeners and jobs off — are system properties of this JVM and
 * must not reach them): every job and listener runs, and a background job failing against the new
 * schema shows in the log, so the log of each is searched for SQL errors. A private PostgreSQL,
 * Redis and SMTP catcher and a RabbitMQ broker (with a virtual host for every run of an
 * application) keep the old release's relay, cleanup jobs and caches away from every other test;
 * this is a deviation from D88's "singletons". The guards: N-1's jar must predate the newest
 * migration (the file is not in it) and must not be HEAD's commit, or the test would compare the
 * application with itself.
 */
class MigrationCompatibilityIT extends IntegrationTestSupport {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String PASSWORD = "correct-horse-battery";
    private static final Duration READY_WITHIN = Duration.ofSeconds(150);
    private static final List<String> SQL_ERRORS =
            List.of("PSQLException", "BadSqlGrammarException", "does not exist");

    private static PostgreSQLContainer<?> postgres;
    private static GenericContainer<?> redis;
    private static GenericContainer<?> smtp;
    private static RabbitMQContainer broker;
    private static Properties previous;
    private static String headJar;
    private static Path logs;

    private record App(String name, Process process, int port, Path log) {
        String url(String path) {
            return "http://localhost:" + port + path;
        }
    }

    private record Outcome(String email, String registrationId) {}

    @BeforeAll
    static void startPrivateInfrastructure() throws IOException {
        previous = new Properties();
        try (InputStream in =
                Files.newInputStream(Path.of(System.getProperty("unihub.test.previous-release")))) {
            previous.load(in);
        }
        headJar = System.getProperty("unihub.test.head-jar");
        logs = Path.of("build", "previous-release", "logs");
        Files.createDirectories(logs);

        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        redis = new GenericContainer<>("redis:7").withExposedPorts(6379);
        smtp = new GenericContainer<>("axllent/mailpit:v1.20.7").withExposedPorts(1025, 8025);
        broker = new RabbitMQContainer("rabbitmq:3.13.7-management");
        List.of(postgres, redis, smtp, broker).parallelStream().forEach(GenericContainer::start);
    }

    @AfterAll
    static void stopPrivateInfrastructure() {
        for (var container : new GenericContainer<?>[] {postgres, redis, smtp, broker}) {
            if (container != null) {
                container.stop();
            }
        }
    }

    // ---------- the guards: N-1 really is the previous release ----------

    @Test
    @DisplayName("the previous release predates the newest migration and is not HEAD")
    void previousRelease_isNotHead() throws Exception {
        var newest = previous.getProperty("newestMigration");
        boolean uncommitted = Boolean.parseBoolean(previous.getProperty("uncommitted"));

        try (var jar = new ZipFile(new File(previous.getProperty("jar")))) {
            assertThat(jar.getEntry("BOOT-INF/classes/db/migration/" + newest))
                    .as(
                            "N-1's jar must not contain %s: it would not be the previous release",
                            newest)
                    .isNull();
        }
        try (var jar = new ZipFile(new File(headJar))) {
            assertThat(jar.getEntry("BOOT-INF/classes/db/migration/" + newest))
                    .as("HEAD's jar contains the newest migration")
                    .isNotNull();
        }
        if (!uncommitted) {
            assertThat(previous.getProperty("commit"))
                    .as("N-1 is the commit before the one that added %s", newest)
                    .isNotEqualTo(previous.getProperty("headCommit"));
        }
    }

    // ---------- 1. the previous release on the schema of HEAD ----------

    @Test
    @Timeout(900)
    @DisplayName("INV-15: the previous release serves its reads and writes on the schema of HEAD")
    void previousReleaseServesTheSchemaOfHead() throws Exception {
        var database = newDatabase("compat_head_schema");
        migrate(database, null);
        var before = schemaHistory(database);

        // the control: HEAD's own jar runs the set, so a step that fails here is a wrong step
        var head = start("head", headJar, database);
        try {
            workload(head, database);
        } finally {
            stop(head);
        }
        assertThat(schemaHistory(database)).as("HEAD never migrates").isEqualTo(before);
        assertNoSqlErrors(head);

        var old = start("previous", previous.getProperty("jar"), database);
        try {
            workload(old, database);
        } finally {
            stop(old);
        }
        assertThat(schemaHistory(database)).as("N-1 never migrates").isEqualTo(before);
        assertNoSqlErrors(old);
    }

    // ---------- 2. the order of a real rollout ----------

    @Test
    @Timeout(900)
    @DisplayName("INV-15: the newest migration is applied while the previous release keeps serving")
    void rolloutWithTheOldReleaseStillRunning() throws Exception {
        var database = newDatabase("compat_rollout");
        var versions = migrate(database, "predecessor");
        Outcome writtenBefore;
        Outcome writtenDuring;

        var old = start("previous-rollout", previous.getProperty("jar"), database);
        try {
            writtenBefore = workload(old, database); // on the schema N-1 was built for
            migrate(database, null); // the newest migration, while N-1 serves
            writtenDuring = workload(old, database); // N-1, now on HEAD's schema
            readsOwnRegistration(old, writtenBefore); // a row it wrote before the migration
        } finally {
            stop(old);
        }
        assertNoSqlErrors(old);
        assertThat(schemaHistory(database)).hasSizeGreaterThan(versions);

        var head = start("head-rollout", headJar, database);
        try {
            readsOwnRegistration(head, writtenBefore); // what N-1 wrote, read by HEAD
            readsOwnRegistration(head, writtenDuring);
            workload(head, database);
        } finally {
            stop(head);
        }
        assertNoSqlErrors(head);
    }

    // ---------- the set of reads and writes ----------

    /** Status codes and ids only: N-1's bodies are never read into HEAD's types. */
    private Outcome workload(App app, Database database) throws Exception {
        var jdbc = database.jdbc();
        var userId = UUID.randomUUID();
        var email = "compat-" + userId + "@example.test";
        jdbc.update(
                "INSERT INTO users (id, email, password_hash, full_name, status)"
                        + " VALUES (?, ?, ?, 'Compat Student', 'ACTIVE')",
                userId,
                email,
                new BCryptPasswordEncoder().encode(PASSWORD));
        jdbc.update(
                "INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE name = 'Student'",
                userId);
        var workshopId =
                WorkshopRows.insertRaw(
                        jdbc,
                        Map.of(
                                "title",
                                "Compat " + userId,
                                "status",
                                "PUBLISHED",
                                "capacity",
                                10,
                                "start_time",
                                Timestamp.valueOf("2030-01-10 09:00:00"),
                                "end_time",
                                Timestamp.valueOf("2030-01-10 11:00:00")));

        var token = login(app, email);
        assertThat(call(app, "GET", "/api/v1/workshops", null, null, null).status())
                .as("catalog")
                .isEqualTo(200);
        assertThat(call(app, "GET", "/api/v1/workshops/" + workshopId, null, null, null).status())
                .as("workshop detail")
                .isEqualTo(200);

        var key = UUID.randomUUID().toString();
        var body = "{\"workshopId\":\"" + workshopId + "\"}";
        var created = call(app, "POST", "/api/v1/registrations", token, body, key);
        assertThat(created.status()).as("free registration: %s", created.body()).isEqualTo(201);
        var registrationId = JSON.readTree(created.body()).get("id").asText();
        var replay = call(app, "POST", "/api/v1/registrations", token, body, key);
        assertThat(replay.status()).as("the same key again").isLessThan(300);
        assertThat(JSON.readTree(replay.body()).get("id").asText()).isEqualTo(registrationId);

        assertThat(
                        call(
                                        app,
                                        "GET",
                                        "/api/v1/registrations/" + registrationId,
                                        token,
                                        null,
                                        null)
                                .status())
                .as("read the registration")
                .isEqualTo(200);
        assertThat(call(app, "GET", "/api/v1/registrations", token, null, null).status())
                .as("list my registrations")
                .isEqualTo(200);
        assertThat(call(app, "GET", "/api/v1/notifications", token, null, null).status())
                .as("notification inbox")
                .isEqualTo(200);
        return new Outcome(email, registrationId);
    }

    private void readsOwnRegistration(App app, Outcome outcome) throws Exception {
        var token = login(app, outcome.email());
        assertThat(
                        call(
                                        app,
                                        "GET",
                                        "/api/v1/registrations/" + outcome.registrationId(),
                                        token,
                                        null,
                                        null)
                                .status())
                .as("%s reads a registration written by the other release", app.name())
                .isEqualTo(200);
    }

    private String login(App app, String email) throws Exception {
        var reply =
                call(
                        app,
                        "POST",
                        "/api/v1/auth/login",
                        null,
                        "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD),
                        null);
        assertThat(reply.status()).as("sign-in on %s: %s", app.name(), reply.body()).isEqualTo(200);
        return JSON.readTree(reply.body()).get("accessToken").asText();
    }

    private record Reply(int status, String body) {}

    private Reply call(
            App app, String method, String path, String token, String body, String idempotencyKey)
            throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create(app.url(path))).timeout(Duration.ofSeconds(30));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        request.method(
                method,
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        var response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }

    // ---------- databases and processes ----------

    private record Database(String url) {
        JdbcTemplate jdbc() {
            return new JdbcTemplate(
                    new DriverManagerDataSource(
                            url, postgres.getUsername(), postgres.getPassword()));
        }
    }

    private static Database newDatabase(String name) {
        new JdbcTemplate(
                        new DriverManagerDataSource(
                                postgres.getJdbcUrl(),
                                postgres.getUsername(),
                                postgres.getPassword()))
                .execute("CREATE DATABASE " + name);
        return new Database(
                "jdbc:postgresql://"
                        + postgres.getHost()
                        + ":"
                        + postgres.getMappedPort(5432)
                        + "/"
                        + name);
    }

    /**
     * Applies the migrations up to {@code target}: {@code null} is HEAD's, {@code "predecessor"} is
     * the one before the newest (the schema N-1 was built for). Returns the number applied so far.
     */
    private static int migrate(Database database, String target) {
        var flyway =
                Flyway.configure()
                        .dataSource(database.url(), postgres.getUsername(), postgres.getPassword());
        if ("predecessor".equals(target)) {
            var all = flyway.load().info().all();
            flyway.target(
                    MigrationVersion.fromVersion(all[all.length - 2].getVersion().getVersion()));
        }
        flyway.load().migrate();
        return schemaHistory(database).size();
    }

    private static List<String> schemaHistory(Database database) {
        return database.jdbc()
                .queryForList(
                        "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank",
                        String.class);
    }

    private App start(String name, String jar, Database database) throws Exception {
        int port = FixedPorts.free();
        int management = FixedPorts.free();
        var log = logs.resolve(name + ".log");
        var command =
                new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-Xmx512m",
                        "-jar",
                        jar);
        // nothing of this JVM's environment (an exported .env of the developer, say) reaches it
        var env = command.environment();
        env.clear();
        env.put("PATH", System.getenv("PATH"));
        env.put("HOME", System.getProperty("user.home"));
        env.put("SERVER_PORT", String.valueOf(port));
        env.put("MANAGEMENT_SERVER_PORT", String.valueOf(management));
        env.put("SPRING_DATASOURCE_URL", database.url());
        env.put("SPRING_DATASOURCE_USERNAME", postgres.getUsername());
        env.put("SPRING_DATASOURCE_PASSWORD", postgres.getPassword());
        env.put("UNIHUB_SECURITY_JWT_SECRET", TestJwts.SECRET);
        env.put(
                "UNIHUB_REDIS_ADDRESS",
                "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));
        env.put("UNIHUB_NOTIFICATION_SMTP_HOST", smtp.getHost());
        env.put("UNIHUB_NOTIFICATION_SMTP_PORT", String.valueOf(smtp.getMappedPort(1025)));
        env.put("UNIHUB_TICKET_SIGNING_KEY", TicketTestKeys.newPrivateJwk());
        env.put("UNIHUB_OBJECT_STORAGE_ENDPOINT", MINIO.getS3URL());
        env.put("UNIHUB_OBJECT_STORAGE_ACCESS_KEY", MINIO.getUserName());
        env.put("UNIHUB_OBJECT_STORAGE_SECRET_KEY", MINIO.getPassword());
        env.put("UNIHUB_OBJECT_STORAGE_CREATE_BUCKET", "true");
        env.put("SPRING_RABBITMQ_VIRTUAL_HOST", newVirtualHost(name));
        env.put("SPRING_RABBITMQ_HOST", broker.getHost());
        env.put("SPRING_RABBITMQ_PORT", String.valueOf(broker.getAmqpPort()));
        env.put("SPRING_RABBITMQ_USERNAME", broker.getAdminUsername());
        env.put("SPRING_RABBITMQ_PASSWORD", broker.getAdminPassword());
        command.redirectErrorStream(true);
        command.redirectOutput(log.toFile());
        var process = command.start();
        var app = new App(name, process, port, log);

        long deadline = System.nanoTime() + READY_WITHIN.toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new AssertionError(
                        name
                                + " exited with "
                                + process.exitValue()
                                + " before it was ready:\n"
                                + tail(app));
            }
            try {
                var ready =
                        HTTP.send(
                                HttpRequest.newBuilder(URI.create(app.url("/api/v1/workshops")))
                                        .timeout(Duration.ofSeconds(3))
                                        .build(),
                                HttpResponse.BodyHandlers.discarding());
                if (ready.statusCode() == 200) {
                    return app;
                }
            } catch (IOException notYet) {
                // still starting
            }
            Thread.sleep(1_000);
        }
        stop(app);
        throw new AssertionError(
                name + " was not ready within " + READY_WITHIN + ":\n" + tail(app));
    }

    /**
     * A virtual host of its own for every run of an application: queues, messages and topology of
     * one run never reach another. Without it a message the previous release left unconsumed when
     * it stopped is taken by the next run against a database it does not belong to (seen:
     * "registration ... does not exist", parked), and each release declares its queues on a broker
     * that has never seen the other's arguments.
     */
    private static String newVirtualHost(String name) throws Exception {
        var vhost = name + "-" + UUID.randomUUID().toString().substring(0, 8);
        assertThat(broker.execInContainer("rabbitmqctl", "add_vhost", vhost).getExitCode())
                .isZero();
        assertThat(
                        broker.execInContainer(
                                        "rabbitmqctl",
                                        "set_permissions",
                                        "-p",
                                        vhost,
                                        broker.getAdminUsername(),
                                        ".*",
                                        ".*",
                                        ".*")
                                .getExitCode())
                .isZero();
        return vhost;
    }

    private static void stop(App app) throws InterruptedException {
        app.process().destroy();
        if (!app.process().waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
            app.process().destroyForcibly().waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private static String tail(App app) throws IOException {
        var lines = Files.readAllLines(app.log(), StandardCharsets.UTF_8);
        return String.join("\n", lines.subList(Math.max(0, lines.size() - 40), lines.size()));
    }

    /** A job that fails against the new schema leaves no HTTP trace; it does leave this. */
    private static void assertNoSqlErrors(App app) throws IOException {
        var log = Files.readString(app.log(), StandardCharsets.UTF_8);
        for (var marker : SQL_ERRORS) {
            assertThat(log)
                    .as("the log of %s (%s) mentions %s", app.name(), app.log(), marker)
                    .doesNotContain(marker);
        }
    }
}
