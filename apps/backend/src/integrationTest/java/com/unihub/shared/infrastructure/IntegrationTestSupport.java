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
    }
}
