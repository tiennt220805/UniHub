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

    /** Registrations that hold a seat: {@code RESERVED} or {@code CONFIRMED}. */
    public int holdingRows(WorkshopId id) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM registrations WHERE workshop_id = ?"
                        + " AND status IN ('RESERVED', 'CONFIRMED')",
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

    /** Statuses of the registration's payments, oldest first. */
    public List<String> paymentStatuses(UUID registrationId) {
        return jdbc.queryForList(
                "SELECT status FROM payments WHERE registration_id = ? ORDER BY initiated_at, id",
                String.class,
                registrationId);
    }

    /** {@code event_publications} rows announcing these payments. */
    public int paymentOutboxRows(UUID registrationId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM event_publications WHERE aggregate_type = 'PAYMENT'"
                        + " AND payload ->> 'registrationId' = ?",
                Integer.class,
                registrationId.toString());
    }

    /** {@code event_publications} rows asking reconciliation to look at this payment. */
    public int reconciliationRequests(UUID paymentId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM event_publications WHERE aggregate_type = 'PAYMENT'"
                        + " AND routing_key = 'unihub.payment.reconciliation.requested'"
                        + " AND aggregate_id = ?",
                Integer.class,
                paymentId);
    }

    /** Makes the payment look as if it had been initiated this long ago. */
    public void backdatePayment(UUID paymentId, java.time.Duration age) {
        jdbc.update(
                "UPDATE payments SET initiated_at = now() - (? * interval '1 second') WHERE id = ?",
                age.toSeconds(),
                paymentId);
    }

    /** Charge keys the user holds, of any status. */
    public int chargeKeys(UserId user) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM idempotency_keys WHERE actor_user_id = ?"
                        + " AND scope = 'PAYMENT_CHARGE'",
                Integer.class,
                user.value());
    }

    /** Status of the user's only charge key ({@code IN_PROGRESS}/{@code COMPLETED}), or null. */
    public String chargeKeyStatus(UserId user) {
        var statuses =
                jdbc.queryForList(
                        "SELECT status FROM idempotency_keys WHERE actor_user_id = ?"
                                + " AND scope = 'PAYMENT_CHARGE'",
                        String.class,
                        user.value());
        return statuses.isEmpty() ? null : statuses.get(0);
    }

    /** Ends the registration's hold in the past, as the clock would. */
    public void expireTheHold(UUID registrationId) {
        jdbc.update(
                "UPDATE registrations SET hold_expires_at = now() - interval '1 minute'"
                        + " WHERE id = ?",
                registrationId);
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
        // Check-in rows first, in two passes: an attendance points at a scan that may have been
        // made by another fixture user, and a scan points at a registration.
        users.forEach(
                user ->
                        jdbc.update(
                                "DELETE FROM attendances WHERE registration_id IN"
                                        + " (SELECT id FROM registrations WHERE user_id = ?)",
                                user));
        users.forEach(
                user ->
                        jdbc.update(
                                "DELETE FROM check_in_scans WHERE scanned_by = ? OR"
                                        + " registration_id IN"
                                        + " (SELECT id FROM registrations WHERE user_id = ?)",
                                user,
                                user));
        users.forEach(
                user -> {
                    // A payment's outbox rows are found through the registration, which still
                    // exists here (nothing deletes it earlier); they must go before it does.
                    jdbc.update(
                            "DELETE FROM event_publications WHERE aggregate_type = 'PAYMENT'"
                                    + " AND (payload ->> 'registrationId' IN"
                                    + " (SELECT id::text FROM registrations WHERE user_id = ?)"
                                    // a reconciliation request names only the payment
                                    + " OR payload ->> 'paymentId' IN"
                                    + " (SELECT p.id::text FROM payments p JOIN registrations r"
                                    + " ON r.id = p.registration_id WHERE r.user_id = ?))",
                            user,
                            user);
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
        // A workshop cancelled or rescheduled over HTTP leaves its own outbox row.
        workshops.forEach(
                id ->
                        jdbc.update(
                                "DELETE FROM event_publications WHERE aggregate_type = 'WORKSHOP'"
                                        + " AND aggregate_id = ?",
                                id));
        workshops.forEach(id -> jdbc.update("DELETE FROM workshops WHERE id = ?", id));
        users.forEach(user -> jdbc.update("DELETE FROM users WHERE id = ?", user));
        users.clear();
        workshops.clear();
    }
}
