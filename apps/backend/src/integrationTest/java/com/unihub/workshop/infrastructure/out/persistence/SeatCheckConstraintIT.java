package com.unihub.workshop.infrastructure.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.shared.infrastructure.IntegrationTestSupport;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M-30 (INV-06, test-design-techniques.md §3.1 row 5): the last layer, with the application taken
 * out of the picture. Every writer here is a plain JDBC connection of its own running the
 * <em>unconditional</em> {@code seats_taken = seats_taken + 1} — no lock, no version, no capacity
 * predicate, nothing a use case would add. The only thing left to refuse the overflow is the CHECK
 * on the column, and it must: the table can never hold {@code seats_taken > capacity}, however the
 * write arrives (database-schema.md §3.1, "never remove the DB-level check constraint as
 * redundant").
 *
 * <p>No Spring context: the point is that nothing of the application participates.
 */
class SeatCheckConstraintIT extends IntegrationTestSupport {
    /** SQLSTATE {@code check_violation}. */
    private static final String CHECK_VIOLATION = "23514";

    private final ExecutorService threads = Executors.newFixedThreadPool(25);
    private final ArrayList<UUID> workshops = new ArrayList<>();

    @AfterEach
    void cleanUp() throws SQLException {
        threads.shutdownNow();
        try (var connection = connect();
                var delete = connection.prepareStatement("DELETE FROM workshops WHERE id = ?")) {
            for (var id : workshops) {
                delete.setObject(1, id);
                delete.executeUpdate();
            }
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private UUID aWorkshop(int capacity, int seatsTaken) throws SQLException {
        var id = UUID.randomUUID();
        try (var connection = connect();
                var insert =
                        connection.prepareStatement(
                                "INSERT INTO workshops (id, title, speaker_name, location,"
                                        + " start_time, end_time, capacity, seats_taken, status)"
                                        + " VALUES (?, 'M-30', 'Speaker', 'Room 1',"
                                        + " now() + interval '1 day', now() + interval '2 days',"
                                        + " ?, ?, 'PUBLISHED')")) {
            insert.setObject(1, id);
            insert.setInt(2, capacity);
            insert.setInt(3, seatsTaken);
            insert.executeUpdate();
        }
        workshops.add(id);
        return id;
    }

    private int seatsTaken(UUID workshop) throws SQLException {
        try (var connection = connect();
                var select =
                        connection.prepareStatement(
                                "SELECT seats_taken FROM workshops WHERE id = ?")) {
            select.setObject(1, workshop);
            try (var rows = select.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    /** What one raw writer saw: null for a success, else the SQLSTATE of the refusal. */
    private CompletableFuture<String> rawIncrement(UUID workshop, CountDownLatch start) {
        return CompletableFuture.supplyAsync(
                () -> {
                    // the connection exists before the latch opens, so the UPDATEs themselves race
                    try (var connection = connect();
                            PreparedStatement update =
                                    connection.prepareStatement(
                                            "UPDATE workshops SET seats_taken = seats_taken + 1"
                                                    + " WHERE id = ?")) {
                        update.setObject(1, workshop);
                        start.await();
                        update.executeUpdate();
                        return null;
                    } catch (SQLException refused) {
                        return refused.getSQLState();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return "interrupted";
                    }
                },
                threads);
    }

    private ArrayList<String> race(UUID workshop, int writers) throws Exception {
        var start = new CountDownLatch(1);
        var outcomes = new ArrayList<CompletableFuture<String>>();
        for (int i = 0; i < writers; i++) {
            outcomes.add(rawIncrement(workshop, start));
        }
        start.countDown();
        var results = new ArrayList<String>();
        for (var outcome : outcomes) {
            results.add(outcome.get(30, TimeUnit.SECONDS));
        }
        return results;
    }

    @Test
    @DisplayName("25 raw writers vs. 3 seats left: exactly 3 land, the rest are check violations")
    void rawConcurrentUpdates_cannotPassCapacity() throws Exception {
        var workshop = aWorkshop(10, 7);

        var outcomes = race(workshop, 25);

        // a refusal must be the CHECK itself, not a deadlock, a timeout or any other failure
        assertThat(outcomes.stream().filter(o -> o == null).count()).isEqualTo(3);
        assertThat(outcomes.stream().filter(o -> o != null).toList())
                .hasSize(22)
                .allMatch(CHECK_VIOLATION::equals);
        assertThat(seatsTaken(workshop)).isEqualTo(10);
    }

    @Test
    @DisplayName("a workshop already at capacity refuses every raw writer and stays at capacity")
    void rawConcurrentUpdates_onAFullWorkshop_allRefused() throws Exception {
        var workshop = aWorkshop(5, 5);

        var outcomes = race(workshop, 25);

        assertThat(outcomes).hasSize(25).allMatch(CHECK_VIOLATION::equals);
        assertThat(seatsTaken(workshop)).isEqualTo(5);
    }

    @Test
    @DisplayName("the refusing constraint is the one on seats_taken, not an unrelated check")
    void theRefusalNamesTheSeatsTakenCheck() throws Exception {
        var workshop = aWorkshop(1, 1);

        try (var connection = connect();
                var update =
                        connection.prepareStatement(
                                "UPDATE workshops SET seats_taken = seats_taken + 1"
                                        + " WHERE id = ?")) {
            update.setObject(1, workshop);
            try {
                update.executeUpdate();
                throw new AssertionError("the overflowing UPDATE was accepted");
            } catch (SQLException refused) {
                assertThat(refused.getSQLState()).isEqualTo(CHECK_VIOLATION);
                var constraint = checkOnSeatsTaken(connection);
                assertThat(refused.getMessage()).contains("\"" + constraint + "\"");
            }
        }
    }

    /** The name Postgres gave the CHECK that mentions seats_taken, read from the catalog. */
    private static String checkOnSeatsTaken(Connection connection) throws SQLException {
        try (var select =
                        connection.prepareStatement(
                                "SELECT conname FROM pg_constraint WHERE conrelid ="
                                        + " 'workshops'::regclass AND contype = 'c'"
                                        + " AND pg_get_constraintdef(oid) LIKE '%seats_taken%'");
                var rows = select.executeQuery()) {
            assertThat(rows.next()).as("a CHECK on workshops.seats_taken exists").isTrue();
            var name = rows.getString(1);
            assertThat(rows.next()).as("exactly one such CHECK").isFalse();
            return name;
        }
    }
}
