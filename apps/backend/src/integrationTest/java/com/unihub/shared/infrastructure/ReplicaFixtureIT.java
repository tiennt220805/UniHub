package com.unihub.shared.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The replica fixture itself works (closes IMPLEMENTATION-READINESS.md F4): the standby really
 * replicates the Master, and pausing replay really makes it stale. M-22's own test (checklist §3.8)
 * builds on this. Explicit setup/teardown, never rollback: the property spans connections.
 */
class ReplicaFixtureIT extends ReplicaIntegrationTestSupport {

    private static final String PROBE_TABLE = "replica_fixture_probe";

    @BeforeEach
    void createProbeTable() throws SQLException {
        try (var master = masterConnection();
                var statement = master.createStatement()) {
            statement.execute("CREATE TABLE " + PROBE_TABLE + " (id int PRIMARY KEY)");
        }
        awaitReplicaCaughtUp();
    }

    @AfterEach
    void dropProbeTable() throws SQLException {
        try (var master = masterConnection();
                var statement = master.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + PROBE_TABLE);
        }
    }

    @Test
    @DisplayName("the replica is a hot standby carrying the migrated schema")
    void replica_isStandbyWithSchema() throws SQLException {
        assertThat(single(replicaConnection(), "SELECT pg_is_in_recovery()::text"))
                .isEqualTo("true");
        assertThat(single(masterConnection(), "SELECT pg_is_in_recovery()::text"))
                .isEqualTo("false");
        assertThat(single(replicaConnection(), "SELECT to_regclass('public.workshops')::text"))
                .isEqualTo("workshops");
    }

    @Test
    @DisplayName("with replay paused a Master write stays invisible on the replica, then converges")
    void pausedReplay_hidesMasterWrite_untilResumed() throws Exception {
        withReplayPaused(
                () -> {
                    try (var master = masterConnection();
                            var statement = master.createStatement()) {
                        statement.execute("INSERT INTO " + PROBE_TABLE + " VALUES (1)");
                    }
                    assertThat(rowCount(masterConnection())).isEqualTo("1");
                    assertThat(rowCount(replicaConnection())).isEqualTo("0");
                });

        awaitReplicaCaughtUp();
        assertThat(rowCount(replicaConnection())).isEqualTo("1");
    }

    @Test
    @DisplayName("the replica refuses writes")
    void replica_isReadOnly() throws SQLException {
        try (var replica = replicaConnection();
                var statement = replica.createStatement()) {
            assertThatThrownBy(
                            () -> statement.execute("INSERT INTO " + PROBE_TABLE + " VALUES (2)"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("read-only transaction");
        }
    }

    private static String rowCount(Connection connection) throws SQLException {
        return single(connection, "SELECT count(*)::text FROM " + PROBE_TABLE);
    }

    private static String single(Connection connection, String sql) throws SQLException {
        try (connection;
                var statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }
}
