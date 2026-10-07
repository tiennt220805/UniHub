package com.unihub.shared.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Base for schema-level ITs: one connection per test, never committed, rolled back afterwards, so
 * the shared (reused) Postgres stays empty for every other IT (test-data-management.md §4). A
 * failed statement aborts the Postgres transaction, so a test asserts at most one violation, as its
 * last step.
 */
public abstract class RolledBackJdbcSupport extends IntegrationTestSupport {

    protected static final String UNIQUE_VIOLATION = "23505";
    protected static final String CHECK_VIOLATION = "23514";
    protected static final String FOREIGN_KEY_VIOLATION = "23503";
    protected static final String NOT_NULL_VIOLATION = "23502";
    protected static final String VALUE_TOO_LONG = "22001";
    protected static final String NUMERIC_OVERFLOW = "22003";

    protected JdbcTemplate jdbc;
    private Connection connection;

    @BeforeEach
    void openRolledBackConnection() throws SQLException {
        connection =
                DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        connection.setAutoCommit(false);
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
    }

    @AfterEach
    void rollbackAndClose() throws SQLException {
        connection.rollback();
        connection.close();
    }

    /** Asserts the statement fails with the given Postgres SQLSTATE (any constraint). */
    protected void assertRejected(Runnable statement, String sqlState) {
        assertRejected(statement, sqlState, null);
    }

    /**
     * Asserts the statement fails with the given SQLSTATE <em>and</em>, when {@code constraint} is
     * not null, that the named constraint is the one Postgres reports. SQLSTATE alone cannot tell
     * two CHECKs apart, so a case could stay green after its own constraint was dropped.
     */
    protected void assertRejected(Runnable statement, String sqlState, String constraint) {
        assertThatThrownBy(statement::run)
                .hasRootCauseInstanceOf(SQLException.class)
                .satisfies(
                        e -> {
                            var root = (SQLException) rootCause(e);
                            assertThat(root.getSQLState()).isEqualTo(sqlState);
                            if (constraint != null) {
                                assertThat(root.getMessage()).contains("\"" + constraint + "\"");
                            }
                        });
    }

    private static Throwable rootCause(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }
}
