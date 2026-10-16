package com.unihub.payment.infrastructure.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.shared.infrastructure.RolledBackJdbcSupport;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Migration {@code V3} (database-schema.md §10.1, §5.1), the {@code payments} half: at most one
 * live payment per registration, and the widened status CHECK. Each violation is asserted by
 * constraint name.
 */
class PaymentSchemaIT extends RolledBackJdbcSupport {

    @Test
    @DisplayName("a dead payment does not block a new live one; refund statuses are accepted")
    void deadPayments_doNotBlockALiveOne() {
        var registration = insertRegistration();

        insertPayment(registration, "FAILED");
        insertPayment(registration, "CANCELLED");
        insertPayment(registration, "REFUND_REQUIRED");
        insertPayment(registration, "REFUNDED");
        insertPayment(registration, "PENDING");

        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM payments WHERE registration_id = ?",
                                Integer.class,
                                registration))
                .isEqualTo(5);
    }

    @Test
    @DisplayName("two live payments for one registration are rejected, PENDING and SUCCEEDED alike")
    void twoLivePayments_areRejected() {
        var registration = insertRegistration();
        insertPayment(registration, "SUCCEEDED");

        assertRejected(
                () -> insertPayment(registration, "PENDING"),
                UNIQUE_VIOLATION,
                "uq_payments_live_per_registration");
    }

    @Test
    @DisplayName("a payment status outside the widened set is rejected")
    void unknownPaymentStatus_isRejected() {
        var registration = insertRegistration();

        assertRejected(
                () -> insertPayment(registration, "REFUNDING"),
                CHECK_VIOLATION,
                "ck_payments_status");
    }

    // ---------- helpers ----------

    private UUID insertRegistration() {
        var user = UUID.randomUUID();
        var workshop = UUID.randomUUID();
        var registration = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, full_name) VALUES (?, ?, 'Test')",
                user,
                user + "@example.test");
        jdbc.update(
                "INSERT INTO workshops (id, title, speaker_name, location, start_time, end_time,"
                        + " capacity) VALUES (?, 't', 's', 'l', now(), now() + interval '1 hour', 5)",
                workshop);
        jdbc.update(
                "INSERT INTO registrations (id, workshop_id, user_id, ticket_type, status)"
                        + " VALUES (?, ?, ?, 'PAID', 'CONFIRMED')",
                registration,
                workshop,
                user);
        return registration;
    }

    private void insertPayment(UUID registration, String status) {
        jdbc.update(
                "INSERT INTO payments (registration_id, amount, status) VALUES (?, 100, ?)",
                registration,
                status);
    }
}
