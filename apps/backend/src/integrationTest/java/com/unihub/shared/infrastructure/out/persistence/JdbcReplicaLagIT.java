package com.unihub.shared.infrastructure.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.shared.infrastructure.ReplicaIntegrationTestSupport;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import javax.sql.DataSource;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * {@code unihub_postgres_replica_lag_seconds} against a real streaming Replica (Phase 7 checklist
 * D99, task 6.2). The formula {@code now() - pg_last_xact_replay_timestamp()} grows on a healthy
 * Replica while the primary is idle, which {@code overlays/loadtest} would show as lag; a Replica
 * that has replayed all it received reads 0, one held back reads the time since its last replay,
 * and a Master (not in recovery) still has nothing to report.
 */
class JdbcReplicaLagIT extends ReplicaIntegrationTestSupport {
    private static final String PROBE_TABLE = "replica_lag_probe";

    private JdbcReplicaLag lagOfReplica;
    private JdbcReplicaLag lagOfMaster;

    @BeforeEach
    void setUp() throws SQLException {
        var master = dataSource(POSTGRES.getJdbcUrl());
        var replica = dataSource(replicaJdbcUrl());
        lagOfReplica =
                new JdbcReplicaLag(
                        master,
                        new StaticListableBeanFactory(Map.of("replica", replica))
                                .getBeanProvider(DataSource.class));
        // No replica bean: the Master pool is asked, as in a local stack.
        lagOfMaster =
                new JdbcReplicaLag(
                        master, new StaticListableBeanFactory().getBeanProvider(DataSource.class));
        try (var connection = masterConnection();
                var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS " + PROBE_TABLE + " (id serial)");
            // A replica reports nothing until it has replayed a transaction: make it replay one.
            statement.execute("INSERT INTO " + PROBE_TABLE + " DEFAULT VALUES");
        }
        awaitReplicaCaughtUp();
    }

    @AfterEach
    void dropProbeTable() throws SQLException {
        try (var connection = masterConnection();
                var statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + PROBE_TABLE);
        }
    }

    @Test
    @DisplayName("a Replica that has replayed everything reads 0 however long the primary is idle")
    void idlePrimaryIsNotLag() throws Exception {
        Thread.sleep(Duration.ofSeconds(2));

        assertThat(lagOfReplica.lagSeconds()).hasValue(0.0);
    }

    @Test
    @DisplayName(
            "a Replica held back reads the time since its last replay, then 0 once it catches up")
    void heldBackReplicaReadsLag() throws Exception {
        withReplayPaused(
                () -> {
                    try (var connection = masterConnection();
                            var statement = connection.createStatement()) {
                        statement.execute("INSERT INTO " + PROBE_TABLE + " DEFAULT VALUES");
                    }
                    // The standby still receives WAL while replay is paused: the receive position
                    // moves ahead of the replay position.
                    Thread.sleep(Duration.ofSeconds(2));
                    assertThat(lagOfReplica.lagSeconds().orElseThrow()).isGreaterThanOrEqualTo(1.5);
                });

        awaitReplicaCaughtUp();
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(lagOfReplica.lagSeconds()).hasValue(0.0));
    }

    @Test
    @DisplayName("an instance that is not in recovery has no lag to report")
    void masterReportsNothing() {
        assertThat(lagOfMaster.lagSeconds()).isEmpty();
    }

    private DataSource dataSource(String jdbcUrl) {
        return new DriverManagerDataSource(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
