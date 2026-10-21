package com.unihub.registration;

import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.domain.id.WorkshopId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Rows the Registration/Payment ITs need around the code under test, committed through plain JDBC
 * (those ITs run real transactions, so nothing rolls back for them). {@link #cleanUp} deletes
 * exactly what the test created, children first, to keep the shared database empty for other ITs.
 */
public final class RegistrationFixtures {
    private final JdbcTemplate jdbc;
    private final List<UUID> users = new ArrayList<>();
    private final List<UUID> workshops = new ArrayList<>();

    public RegistrationFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UserId aStudent() {
        var id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, full_name) VALUES (?, ?, 'Test Student')",
                id,
                id + "@test.local");
        users.add(id);
        return UserId.of(id);
    }

    /** Registers a workshop created by the test (e.g. through the repository) for clean-up. */
    public WorkshopId track(WorkshopId id) {
        workshops.add(id.value());
        return id;
    }

    public int seatsTaken(WorkshopId id) {
        return jdbc.queryForObject(
                "SELECT seats_taken FROM workshops WHERE id = ?", Integer.class, id.value());
    }

    public long workshopVersion(WorkshopId id) {
        return jdbc.queryForObject(
                "SELECT version FROM workshops WHERE id = ?", Long.class, id.value());
    }

    public int idempotencyKeys(UserId user) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM idempotency_keys WHERE actor_user_id = ?",
                Integer.class,
                user.value());
    }

    /** {@code result_type} of the user's only key, or null when it has none. */
    public String recordedResultType(UserId user) {
        var types =
                jdbc.queryForList(
                        "SELECT result_type FROM idempotency_keys WHERE actor_user_id = ?",
                        String.class,
                        user.value());
        return types.isEmpty() ? null : types.get(0);
    }

    public int registrationRows(WorkshopId id) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM registrations WHERE workshop_id = ?",
                Integer.class,
                id.value());
    }

    /** {@code event_publications} rows announcing this registration (INV-10). */
    public int outboxRows(UUID registrationId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM event_publications WHERE aggregate_id = ?",
                Integer.class,
                registrationId);
    }

    /** {@code event_publications} rows for the workshop's registrations, whatever their state. */
    public int outboxRowsForWorkshop(WorkshopId id) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM event_publications WHERE aggregate_type = 'REGISTRATION'"
                        + " AND payload ->> 'workshopId' = ?",
                Integer.class,
                id.value().toString());
    }

    /** The routing keys of those rows. */
    public List<String> outboxRoutingKeysForWorkshop(WorkshopId id) {
        return jdbc.queryForList(
                "SELECT routing_key FROM event_publications WHERE aggregate_type = 'REGISTRATION'"
                        + " AND payload ->> 'workshopId' = ?",
                String.class,
                id.value().toString());
    }

    public void cleanUp() {
        users.forEach(
                user -> {
                    // By the payload's userId, not through registrations: a test may already have
                    // deleted the registration row, and its outbox row would stay PENDING for the
                    // next relay (any later IT with the relay on would publish it to the broker).
                    jdbc.update(
                            "DELETE FROM event_publications WHERE aggregate_type = 'REGISTRATION'"
                                    + " AND payload ->> 'userId' = ?",
                            user.toString());
                    jdbc.update("DELETE FROM idempotency_keys WHERE actor_user_id = ?", user);
                    jdbc.update(
                            "DELETE FROM payments WHERE registration_id IN "
                                    + "(SELECT id FROM registrations WHERE user_id = ?)",
                            user);
                    jdbc.update("DELETE FROM registrations WHERE user_id = ?", user);
                });
        // By the workshop too: a hold-expiry job carries no userId (backend-coding-standards.md
        // §8.4), and either row must go before a later IT relays it onto the shared broker.
        workshops.forEach(
                id ->
                        jdbc.update(
                                "DELETE FROM event_publications WHERE aggregate_type ="
                                        + " 'REGISTRATION' AND payload ->> 'workshopId' = ?",
                                id.toString()));
        workshops.forEach(id -> jdbc.update("DELETE FROM workshops WHERE id = ?", id));
        users.forEach(user -> jdbc.update("DELETE FROM users WHERE id = ?", user));
        users.clear();
        workshops.clear();
    }
}
