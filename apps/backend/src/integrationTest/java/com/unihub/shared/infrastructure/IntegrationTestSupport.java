package com.unihub.shared.infrastructure;

import com.unihub.registration.infrastructure.out.crypto.TicketTestKeys;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

public abstract class IntegrationTestSupport {

    /**
     * Boot attaches every Prometheus registry to {@code Metrics.globalRegistry} and never detaches
     * it when its context closes. A context that exports to Prometheus (the extra application
     * instances of {@code MultiInstanceHarness}, which are not {@code @SpringBootTest}s and so do
     * not get its "no export" setting, and the classes that ask for
     * {@code @AutoConfigureObservability}) therefore leaves a registry behind that outlives its
     * context. Its counters stay in the global composite, and the next test that increments a
     * global meter ({@code Metrics.counter}, which every module's metrics use) makes the registry
     * sample an exemplar, which reaches into the closed context for a tracer and fails. It became
     * visible when the number of cached contexts passed Spring's limit of 32 and the oldest began
     * to be closed mid-run (Phase 6 checklist 2.6, 2.7).
     */
    @AfterAll
    static void detachPrometheusRegistries() {
        for (var registry : new java.util.ArrayList<>(Metrics.globalRegistry.getRegistries())) {
            if (registry instanceof PrometheusMeterRegistry) {
                Metrics.removeRegistry(registry);
            }
        }
    }

    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine").withReuse(true);

    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7").withExposedPorts(6379);

    protected static final RabbitMQContainer RABBITMQ =
            new RabbitMQContainer("rabbitmq:3.13.7-management");

    /**
     * SMTP catcher for the EMAIL channel (Phase 4 checklist D36): accepts every message and keeps
     * it, readable through its HTTP API ({@link Mailpit}). A test double, never a delivery path.
     * Pinned to the same version as docker-compose.yml.
     */
    protected static final GenericContainer<?> MAILPIT =
            new GenericContainer<>("axllent/mailpit:v1.20.7").withExposedPorts(1025, 8025);

    // minio/minio stopped being pullable (Docker Hub repository removed, 10/2025 — verified against
    // live Docker Hub/quay.io, not a sandbox/network issue). cgr.dev/chainguard/minio is a real
    // MinIO build under a different registry/namespace; asCompatibleSubstituteFor is
    // Testcontainers'
    // own mechanism for exactly this case — it does not skip the container's actual
    // startup/health-check, only MinIOContainer's constructor-time image-name assertion.
    // Pinned by digest (RELEASE.2026-09-22T19-25-18Z, built 2026-09-30), the same one as
    // docker-compose.yml: Chainguard's free images only carry `latest`, which a digest freezes.
    protected static final MinIOContainer MINIO =
            new MinIOContainer(
                    DockerImageName.parse(
                                    "cgr.dev/chainguard/minio@sha256:"
                                            + "4692462f35d97d7e82c30371d82f057703c5d9489bcae726010594c812f2d285")
                            .asCompatibleSubstituteFor("minio/minio"));

    /**
     * {@code redis://host:port} of the singleton {@link #REDIS}, for classes that cannot extend
     * this one.
     */
    public static String redisAddress() {
        return "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379);
    }

    /**
     * Empties the singleton Redis before every test (checklist D21). Redis is required
     * configuration, so every context has the workshop catalog cache live, and a test that writes
     * PostgreSQL directly and then reads through the API would otherwise read an entry an earlier
     * test left. Also drops any lock a previous test abandoned. An inline command over a plain
     * socket: a {@code docker exec} per test costs far more than the test it protects.
     */
    @BeforeEach
    protected void flushRedis() throws IOException {
        try (var socket = new Socket(REDIS.getHost(), REDIS.getMappedPort(6379))) {
            socket.getOutputStream().write("FLUSHALL\r\n".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            var reply =
                    new String(socket.getInputStream().readNBytes(3), StandardCharsets.US_ASCII);
            if (!reply.startsWith("+OK")) {
                throw new IllegalStateException("FLUSHALL was refused by the test Redis: " + reply);
            }
        }
    }

    static {
        // Started once, when this class is first loaded by the JVM. No @Container/@Testcontainers:
        // those
        // give per-class start/stop (docs/testing/test-levels/integration-testing.md §2).
        // Testcontainers'
        // Ryuk reaps these after JVM exit.
        POSTGRES.start();
        REDIS.start();
        RABBITMQ.start();
        MINIO.start();
        MAILPIT.start();

        // Flyway Java API, not spring.flyway.enabled (application.yml keeps that false) — INV-15:
        // migration
        // never runs from inside the application itself, including this test's Spring context.
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();

        // Every Spring context in this JVM talks to this broker. System properties (not a per-class
        // @DynamicPropertySource) so a context that only happens to publish — the outbox relay runs
        // in
        // every full context — never falls back to localhost:5672. A test's own
        // @DynamicPropertySource
        // still overrides them.
        // Redis is required configuration (checklist D21): every context gets the singleton, so
        // the seat lock and the job lock are the Redis ones everywhere. A test's own
        // @DynamicPropertySource (an unreachable address, a private container) still overrides it.
        System.setProperty("unihub.redis.address", redisAddress());

        // The SMTP host is required configuration (Phase 4 checklist D36): every context gets the
        // singleton Mailpit, which also keeps the mail of a delivery a test did not mean to make
        // out
        // of the real network. A test's own @DynamicPropertySource (a dead address) still overrides
        // it.
        System.setProperty("unihub.notification.smtp.host", MAILPIT.getHost());

        // Brochure storage (Phase 5 checklist 9.1, workshop.md §4): every context gets the
        // singleton
        // MinIO through the same S3 client the application uses, with the bucket created on start.
        // A test that needs the bucket down uses a container of its own (ObjectStorageOutageIT).
        System.setProperty("unihub.object-storage.endpoint", MINIO.getS3URL());
        System.setProperty("unihub.object-storage.access-key", MINIO.getUserName());
        System.setProperty("unihub.object-storage.secret-key", MINIO.getPassword());
        System.setProperty("unihub.object-storage.create-bucket", "true");
        System.setProperty(
                "unihub.notification.smtp.port", String.valueOf(MAILPIT.getMappedPort(1025)));

        // The ticket signing key is required configuration (Phase 5 checklist D49): every context
        // gets a key generated for this JVM, never one read from a file or committed. A test's own
        // @DynamicPropertySource or command-line argument still overrides it.
        System.setProperty("unihub.ticket.signing-key", TicketTestKeys.newPrivateJwk());

        // Actuator listens on its own management port, 8081 in application.yml (Phase 6 checklist
        // D64).
        // Spring caches one context per test configuration and every one binds its own Tomcat, so a
        // fixed port would fail the second context with "port in use". 0 picks a free port; a test
        // reads it with @LocalManagementPort.
        System.setProperty("management.server.port", "0");

        System.setProperty("spring.rabbitmq.host", RABBITMQ.getHost());
        System.setProperty("spring.rabbitmq.port", String.valueOf(RABBITMQ.getAmqpPort()));
        System.setProperty("spring.rabbitmq.username", RABBITMQ.getAdminUsername());
        System.setProperty("spring.rabbitmq.password", RABBITMQ.getAdminPassword());

        // Spring caches one context per distinct test configuration, and each keeps a Master pool
        // open
        // for the rest of the run. At Hikari's default of 10 connections a dozen cached contexts
        // exhaust the container's 100 connection slots ("too many clients"). Five is enough for the
        // concurrent-transaction tests, which hold at most three.
        System.setProperty("spring.datasource.hikari.maximum-pool-size", "5");
        System.setProperty("spring.datasource.hikari.minimum-idle", "1");

        // The outbox relay would publish rows other tests commit, while they are still asserting on
        // them. Contexts do not start it; a test that exercises it enables it for its own context
        // or
        // calls the use case directly.
        System.setProperty("unihub.jobs.outbox-relay.enabled", "false");
        System.setProperty("unihub.jobs.event-publication-cleanup.enabled", "false");

        // Unlike the cleanup sweeps this one is not harmless: it would take the PENDING payments
        // other tests leave behind and write reconciliation requests for them, which the relay
        // would push into queues another test counts. A test drives the sweep by calling the use
        // case.
        System.setProperty("unihub.jobs.payment-reconciliation.enabled", "false");

        // The same for consumers. Spring caches contexts across test classes, so a listener
        // container in any cached context would take messages that another test publishes and
        // counts (RabbitTopologyIT counts the messages waiting on every business queue). A test
        // that
        // exercises a consumer starts them for its own context and closes it afterwards.
        System.setProperty("spring.rabbitmq.listener.simple.auto-startup", "false");
    }
}
