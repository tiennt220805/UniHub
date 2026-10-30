package com.unihub.checkin.infrastructure.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.shared.infrastructure.RolledBackJdbcSupport;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves migration {@code V6} lets a scan the device rejected reach the server with no
 * registration, and only as {@code REJECTED_INVALID} (Phase 5 checklist D50; database-schema.md
 * §6.1, §10.3). The old guarantees stay: a scan that does name a registration still needs it to
 * exist.
 */
class CheckInSchemaIT extends RolledBackJdbcSupport {

    private static final String UNRESOLVED_IS_INVALID = "chk_check_in_scans_unresolved_is_invalid";

    @Test
    @DisplayName("AC50.1: a scan with no registration is stored when it is REJECTED_INVALID")
    void unresolvedScan_isStoredAsRejectedInvalid() {
        insertScan(null, "REJECTED_INVALID");

        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM check_in_scans WHERE registration_id IS NULL",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("AC50.1: a scan with no registration cannot be ACCEPTED")
    void unresolvedScan_cannotBeAccepted() {
        assertRejected(() -> insertScan(null, "ACCEPTED"), CHECK_VIOLATION, UNRESOLVED_IS_INVALID);
    }

    @Test
    @DisplayName("AC50.1: a scan with no registration cannot be REJECTED_DUPLICATE either")
    void unresolvedScan_cannotBeADuplicate() {
        assertRejected(
                () -> insertScan(null, "REJECTED_DUPLICATE"),
                CHECK_VIOLATION,
                UNRESOLVED_IS_INVALID);
    }

    @Test
    @DisplayName("a scan with a registration keeps every outcome")
    void resolvedScan_keepsEveryOutcome() {
        var registration = insertRegistration();

        insertScan(registration, "ACCEPTED");
        insertScan(registration, "REJECTED_DUPLICATE");
        insertScan(registration, "REJECTED_INVALID");

        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM check_in_scans WHERE registration_id = ?",
                                Integer.class,
                                registration))
                .isEqualTo(3);
    }

    @Test
    @DisplayName(
            "a scan that names a registration which does not exist is still a foreign-key error")
    void unknownRegistration_isStillRejected() {
        assertRejected(
                () -> insertScan(UUID.randomUUID(), "REJECTED_INVALID"), FOREIGN_KEY_VIOLATION);
    }

    @Test
    @DisplayName("scanned_by stays NOT NULL: the audit trail always names a staff member")
    void scannedBy_staysRequired() {
        assertRejected(
                () ->
                        jdbc.update(
                                "INSERT INTO check_in_scans (id, registration_id, scanned_by,"
                                        + " device_id, scanned_at, outcome) VALUES (?, NULL, NULL,"
                                        + " 'd', now(), 'REJECTED_INVALID')",
                                UUID.randomUUID()),
                NOT_NULL_VIOLATION);
    }

    // ---------- helpers ----------

    private UUID insertUser() {
        var id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, full_name) VALUES (?, ?, 'Staff')",
                id,
                "c-" + id + "@example.test");
        return id;
    }

    private UUID insertRegistration() {
        var user = insertUser();
        var workshop = UUID.randomUUID();
        var registration = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO workshops (id, title, speaker_name, location, start_time, end_time,"
                        + " capacity) VALUES (?, 't', 's', 'l', now(), now() + interval '1 hour', 5)",
                workshop);
        jdbc.update(
                "INSERT INTO registrations (id, workshop_id, user_id, ticket_type, status)"
                        + " VALUES (?, ?, ?, 'FREE', 'CONFIRMED')",
                registration,
                workshop,
                user);
        return registration;
    }

    private void insertScan(UUID registration, String outcome) {
        jdbc.update(
                "INSERT INTO check_in_scans (id, registration_id, scanned_by, device_id,"
                        + " scanned_at, outcome) VALUES (?, ?, ?, 'device-1', now(), ?)",
                UUID.randomUUID(),
                registration,
                insertUser(),
                outcome);
    }
}
