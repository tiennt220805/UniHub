package com.unihub.shared.infrastructure;

import org.flywaydb.core.Flyway;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

public abstract class IntegrationTestSupport {

    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine").withReuse(true);

    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7").withExposedPorts(6379);

    protected static final RabbitMQContainer RABBITMQ =
            new RabbitMQContainer("rabbitmq:3-management");

    // minio/minio stopped being pullable (Docker Hub repository removed, 10/2025 — verified against
    // live Docker Hub/quay.io, not a sandbox/network issue). cgr.dev/chainguard/minio is a real
    // MinIO
    // build under a different registry/namespace; asCompatibleSubstituteFor is Testcontainers' own
    // mechanism for exactly this case — it does not skip the container's actual
    // startup/health-check,
    // only MinIOContainer's constructor-time image-name assertion.
    protected static final MinIOContainer MINIO =
            new MinIOContainer(
                    DockerImageName.parse("cgr.dev/chainguard/minio:latest")
                            .asCompatibleSubstituteFor("minio/minio"));

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

        // The same for consumers. Spring caches contexts across test classes, so a listener
        // container in any cached context would take messages that another test publishes and
        // counts (RabbitTopologyIT counts the messages waiting on every business queue). A test
        // that
        // exercises a consumer starts them for its own context and closes it afterwards.
        System.setProperty("spring.rabbitmq.listener.simple.auto-startup", "false");
    }
}
