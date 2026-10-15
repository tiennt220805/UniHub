package com.unihub.shared.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for INV-03 tests (integration-testing.md §1–§2, resilience-testing.md §2.5): a real
 * PostgreSQL streaming replica of {@link #POSTGRES}, whose WAL replay a test can pause so that a
 * misrouted read actually sees stale data. A caught-up replica could not reveal one.
 *
 * <p>The replica starts once per JVM, and only if a subclass is loaded. It is never reused across
 * runs ({@code withReuse} is not set): a standby is valid only against the exact Master it was
 * cloned from. Every test ends with replay resumed — {@link #resumeReplayAfterEachTest} asserts it
 * — because a replica left paused would make every later test read stale.
 *
 * <p>Ordinary {@code *IT}s keep pointing both datasources at the Master; only subclasses set {@code
 * unihub.datasource.replica.url} to {@link #replicaJdbcUrl()}.
 */
public abstract class ReplicaIntegrationTestSupport extends IntegrationTestSupport {

    private static final int POSTGRES_PORT = 5432;
    private static final Duration CATCH_UP_TIMEOUT = Duration.ofSeconds(30);

    protected static final GenericContainer<?> POSTGRES_REPLICA;

    static {
        // Superclass static init has already started and migrated the Master, so the base backup
        // carries the full schema.
        allowReplicationConnections();
        POSTGRES_REPLICA = standbyOf(masterAddressOnDockerNetwork());
        POSTGRES_REPLICA.start();
    }

    protected static String replicaJdbcUrl() {
        return "jdbc:postgresql://"
                + POSTGRES_REPLICA.getHost()
                + ":"
                + POSTGRES_REPLICA.getMappedPort(POSTGRES_PORT)
                + "/"
                + POSTGRES.getDatabaseName();
    }

    /**
     * Stops the replica applying WAL. Once this returns, no Master write made afterwards becomes
     * visible on the replica until {@link #resumeReplay()}.
     */
    protected static void pauseReplay() {
        execute(replicaConnection(), "SELECT pg_wal_replay_pause()");
    }

    /** Resumes WAL replay and asserts that it really is no longer paused. */
    protected static void resumeReplay() {
        execute(replicaConnection(), "SELECT pg_wal_replay_resume()");
        assertThat(queryBoolean(replicaConnection(), "SELECT pg_is_wal_replay_paused()"))
                .as("replica WAL replay must be running again")
                .isFalse();
    }

    /** Runs {@code body} with replay paused; replay is resumed even if {@code body} fails. */
    protected static void withReplayPaused(ThrowingRunnable body) throws Exception {
        pauseReplay();
        try {
            body.run();
        } finally {
            resumeReplay();
        }
    }

    /** Waits until the replica has replayed everything the Master has written so far. */
    protected static void awaitReplicaCaughtUp() {
        String masterLsn = queryString(masterConnection(), "SELECT pg_current_wal_lsn()::text");
        Awaitility.await()
                .atMost(CATCH_UP_TIMEOUT)
                .pollInterval(Duration.ofMillis(50))
                .until(
                        () ->
                                queryBoolean(
                                        replicaConnection(),
                                        "SELECT pg_last_wal_replay_lsn() >= '"
                                                + masterLsn
                                                + "'::pg_lsn"));
    }

    protected static Connection masterConnection() {
        return connect(POSTGRES.getJdbcUrl());
    }

    protected static Connection replicaConnection() {
        return connect(replicaJdbcUrl());
    }

    @AfterEach
    void resumeReplayAfterEachTest() {
        resumeReplay();
    }

    /**
     * The default pg_hba.conf of the postgres image has no remote {@code replication} entry ({@code
     * all} in its database column does not match replication connections). Appended once — the
     * Master may be a reused container that already has it — then reloaded. The Testcontainers user
     * is a superuser, so it already holds the REPLICATION attribute.
     */
    private static void allowReplicationConnections() {
        String entry = "host replication all all scram-sha-256";
        ExecResult result =
                exec(
                        "sh",
                        "-c",
                        "grep -qxF '"
                                + entry
                                + "' \"$PGDATA/pg_hba.conf\" || echo '"
                                + entry
                                + "' >> \"$PGDATA/pg_hba.conf\"");
        if (result.getExitCode() != 0) {
            throw new IllegalStateException("could not edit pg_hba.conf: " + result.getStderr());
        }
        execute(masterConnection(), "SELECT pg_reload_conf()");
    }

    /**
     * Master and replica both sit on Docker's default bridge network, where they reach each other
     * by container IP. A custom Network would not do: it would change on every run, which defeats
     * the Master's local {@code withReuse(true)}.
     */
    private static String masterAddressOnDockerNetwork() {
        return POSTGRES.getContainerInfo().getNetworkSettings().getNetworks().values().stream()
                .map(network -> network.getIpAddress())
                .filter(ip -> ip != null && !ip.isBlank())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Master has no container IP"));
    }

    /**
     * Clones the Master with pg_basebackup ({@code --write-recovery-conf} adds standby.signal and
     * primary_conninfo), then hands over to the image's own entrypoint, which finds an existing
     * data directory, skips initdb and starts postgres as a hot standby.
     */
    private static GenericContainer<?> standbyOf(String masterHost) {
        String script =
                "set -e\n"
                        + "su-exec postgres pg_basebackup --host="
                        + masterHost
                        + " --port="
                        + POSTGRES_PORT
                        + " --username="
                        + POSTGRES.getUsername()
                        + " --pgdata=\"$PGDATA\" --wal-method=stream --write-recovery-conf"
                        + " --checkpoint=fast --no-password\n"
                        + "exec docker-entrypoint.sh postgres\n";
        return new GenericContainer<>(DockerImageName.parse("postgres:16-alpine"))
                .withEnv("PGPASSWORD", POSTGRES.getPassword())
                .withCommand("sh", "-c", script)
                .withExposedPorts(POSTGRES_PORT)
                .waitingFor(
                        Wait.forLogMessage(
                                        ".*database system is ready to accept read-only connections.*\\n",
                                        1)
                                .withStartupTimeout(Duration.ofMinutes(2)));
    }

    private static ExecResult exec(String... command) {
        try {
            return POSTGRES.execInContainer(command);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static Connection connect(String jdbcUrl) {
        try {
            return DriverManager.getConnection(
                    jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void execute(Connection connection, String sql) {
        try (connection;
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean queryBoolean(Connection connection, String sql) {
        try (connection;
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getBoolean(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String queryString(Connection connection, String sql) {
        try (connection;
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @FunctionalInterface
    protected interface ThrowingRunnable {
        void run() throws Exception;
    }
}
