package com.unihub.checkin;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aPublishedWorkshop;

import com.unihub.registration.RegistrationFixtures;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Rows the Check-in ITs need around the code under test: staff, workshops, registrations that carry
 * a ticket code, committed through plain JDBC. Everything is registered with the {@link
 * RegistrationFixtures} it wraps, whose {@code cleanUp} also removes the scans and attendances of
 * these registrations and staff.
 */
public final class CheckInFixtures {

    /** A registration the test made, with the code a device would scan. */
    public record Ticket(UUID registrationId, WorkshopId workshopId, String code, UserId student) {}

    private final RegistrationFixtures base;
    private final JdbcTemplate jdbc;
    private final WorkshopRepository workshops;
    private final TransactionTemplate tx;

    public CheckInFixtures(
            JdbcTemplate jdbc, WorkshopRepository workshops, TransactionTemplate tx) {
        this.base = new RegistrationFixtures(jdbc);
        this.jdbc = jdbc;
        this.workshops = workshops;
        this.tx = tx;
    }

    public UserId aStaffMember() {
        return base.aStudent();
    }

    public WorkshopId aWorkshop() {
        return aWorkshop(50);
    }

    public WorkshopId aWorkshop(int capacity) {
        var workshop = aPublishedWorkshop().withCapacity(capacity).build();
        tx.executeWithoutResult(s -> workshops.save(workshop));
        return base.track(workshop.id());
    }

    public Ticket aConfirmedTicket(WorkshopId workshop) {
        return aTicket(workshop, "CONFIRMED");
    }

    public Ticket aTicket(WorkshopId workshop, String status) {
        var student = base.aStudent();
        var id = UUID.randomUUID();
        var code = UUID.randomUUID().toString().replace("-", "").substring(0, 26).toUpperCase();
        var held = status.equals("RESERVED");
        jdbc.update(
                "INSERT INTO registrations (id, workshop_id, user_id, ticket_type, status,"
                        + " hold_expires_at, ticket_code, qr_payload) VALUES (?, ?, ?, ?, ?,"
                        + " CASE WHEN ? THEN now() + interval '10 minutes' END, ?, ?)",
                id,
                workshop.value(),
                student.value(),
                held ? "PAID" : "FREE",
                status,
                held,
                code,
                "payload-" + code);
        return new Ticket(id, workshop, code, student);
    }

    public void cleanUp() {
        base.cleanUp();
    }
}
