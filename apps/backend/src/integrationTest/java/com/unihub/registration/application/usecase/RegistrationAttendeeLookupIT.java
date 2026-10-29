package com.unihub.registration.application.usecase;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aPublishedWorkshop;
import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.registration.RegistrationFixtures;
import com.unihub.registration.api.Attendee;
import com.unihub.registration.api.WorkshopAttendees;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.security.TestJwts;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The read Notification uses to learn who a workshop change concerns (Phase 4 checklist D35, AC35.1
 * at its source): registrations that hold a seat, none of the ended ones.
 */
@SpringBootTest
class RegistrationAttendeeLookupIT extends IntegrationTestSupport {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> TestJwts.SECRET);
    }

    @Autowired private WorkshopAttendees attendees;
    @Autowired private WorkshopRepository workshops;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate tx;

    private RegistrationFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new RegistrationFixtures(jdbc);
    }

    @AfterEach
    void cleanUp() {
        fixtures.cleanUp();
    }

    private WorkshopId aWorkshop() {
        var workshop = aPublishedWorkshop().withCapacity(20).build();
        tx.executeWithoutResult(s -> workshops.save(workshop));
        return fixtures.track(workshop.id());
    }

    /** {@code createdAt} is set explicitly so the "oldest first" order is not a coincidence. */
    private UUID registration(WorkshopId workshop, UserId student, String status, int minutesAgo) {
        var id = UUID.randomUUID();
        var held = status.equals("RESERVED");
        jdbc.update(
                "INSERT INTO registrations (id, workshop_id, user_id, ticket_type, status,"
                        + " hold_expires_at, created_at) VALUES (?, ?, ?, ?, ?,"
                        + " CASE WHEN ? THEN now() + interval '10 minutes' END,"
                        + " now() - make_interval(mins => ?))",
                id,
                workshop.value(),
                student.value(),
                held ? "PAID" : "FREE",
                status,
                held,
                minutesAgo);
        return id;
    }

    @Test
    @DisplayName("only CONFIRMED and RESERVED hold a seat: EXPIRED and CANCELLED are left out")
    void holdingSeatsFor_returnsConfirmedAndReservedOnly() {
        var workshop = aWorkshop();
        var confirmed = fixtures.aStudent();
        var reserved = fixtures.aStudent();
        var expired = fixtures.aStudent();
        var cancelled = fixtures.aStudent();
        var confirmedId = registration(workshop, confirmed, "CONFIRMED", 40);
        var reservedId = registration(workshop, reserved, "RESERVED", 30);
        registration(workshop, expired, "EXPIRED", 20);
        registration(workshop, cancelled, "CANCELLED", 10);

        var found = attendees.holdingSeatsFor(workshop);

        assertThat(found)
                .containsExactly(
                        new Attendee(RegistrationId.of(confirmedId), confirmed, workshop),
                        new Attendee(RegistrationId.of(reservedId), reserved, workshop));
    }

    @Test
    @DisplayName("oldest first, so a retried fan-out walks the same order")
    void holdingSeatsFor_isOldestFirst() {
        var workshop = aWorkshop();
        var newest = fixtures.aStudent();
        var oldest = fixtures.aStudent();
        var middle = fixtures.aStudent();
        registration(workshop, newest, "CONFIRMED", 1);
        registration(workshop, oldest, "CONFIRMED", 90);
        registration(workshop, middle, "CONFIRMED", 45);

        assertThat(attendees.holdingSeatsFor(workshop))
                .extracting(Attendee::userId)
                .containsExactly(oldest, middle, newest);
    }

    @Test
    @DisplayName("another workshop's registrations are never returned")
    void holdingSeatsFor_isScopedToTheWorkshop() {
        var workshop = aWorkshop();
        var other = aWorkshop();
        var student = fixtures.aStudent();
        registration(other, student, "CONFIRMED", 5);

        assertThat(attendees.holdingSeatsFor(workshop)).isEmpty();
    }

    @Test
    @DisplayName("a workshop with no registrations, or no such workshop, gives an empty list")
    void holdingSeatsFor_isEmptyWhenNobodyIsRegistered() {
        assertThat(attendees.holdingSeatsFor(aWorkshop())).isEmpty();
        assertThat(attendees.holdingSeatsFor(WorkshopId.newId())).isEmpty();
    }

    @Test
    @DisplayName("find answers for a registration in any status, and is empty for an unknown one")
    void find_answersForAnyStatus() {
        var workshop = aWorkshop();
        var student = fixtures.aStudent();
        var id = registration(workshop, student, "CANCELLED", 5);

        assertThat(attendees.find(RegistrationId.of(id)))
                .contains(new Attendee(RegistrationId.of(id), student, workshop));
        assertThat(attendees.find(RegistrationId.newId())).isEmpty();
    }
}
