package com.unihub.shared.infrastructure.routing;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aPublishedWorkshop;
import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.registration.RegistrationFixtures;
import com.unihub.registration.api.WorkshopAttendees;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.infrastructure.ReplicaIntegrationTestSupport;
import com.unihub.shared.security.TestJwts;
import com.unihub.user.api.UserContacts;
import com.unihub.workshop.api.WorkshopSummaries;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The reads Notification makes on a freshly written fact go to the Master (Phase 4 checklist D35,
 * backend-coding-standards.md §20; INV-03 in spirit): the consumer runs right after the write that
 * produced the event, and a lagging Replica would show a registration, a user or a rescheduled
 * workshop as it was before. Replay is paused so the Replica really is behind, and a control read
 * proves it.
 */
@SpringBootTest
class NotificationReadsMasterIT extends ReplicaIntegrationTestSupport {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add(
                "unihub.datasource.replica.url", ReplicaIntegrationTestSupport::replicaJdbcUrl);
        registry.add("unihub.security.jwt.secret", () -> TestJwts.SECRET);
        registry.add("unihub.jobs.idempotency-cleanup.enabled", () -> "false");
    }

    @Autowired private UserContacts userContacts;
    @Autowired private WorkshopSummaries workshopSummaries;
    @Autowired private WorkshopAttendees attendees;
    @Autowired private WorkshopRepository workshops;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;

    private RegistrationFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new RegistrationFixtures(jdbc);
    }

    @AfterEach
    void cleanUp() {
        fixtures.cleanUp();
    }

    private int rowsOnReplica(String table, UUID id) {
        var readOnly = new TransactionTemplate(transactions);
        readOnly.setReadOnly(true);
        return readOnly.execute(
                s ->
                        jdbc.queryForObject(
                                "SELECT count(*) FROM " + table + " WHERE id = ?",
                                Integer.class,
                                id));
    }

    @Test
    @DisplayName(
            "a user, a workshop and a registration written a moment ago are all found while the"
                    + " Replica is behind")
    void freshWrites_areReadFromTheMaster() throws Exception {
        awaitReplicaCaughtUp();

        withReplayPaused(
                () -> {
                    var student = fixtures.aStudent();
                    var workshop = aPublishedWorkshop().withTitle("Just rescheduled").build();
                    new TransactionTemplate(transactions)
                            .executeWithoutResult(s -> workshops.save(workshop));
                    fixtures.track(workshop.id());
                    var registrationId = UUID.randomUUID();
                    jdbc.update(
                            "INSERT INTO registrations (id, workshop_id, user_id, ticket_type,"
                                    + " status) VALUES (?, ?, ?, 'FREE', 'CONFIRMED')",
                            registrationId,
                            workshop.id().value(),
                            student.value());

                    assertThat(rowsOnReplica("users", student.value()))
                            .as("control: the Replica has not seen the user")
                            .isZero();
                    assertThat(rowsOnReplica("workshops", workshop.id().value()))
                            .as("control: the Replica has not seen the workshop")
                            .isZero();
                    assertThat(rowsOnReplica("registrations", registrationId))
                            .as("control: the Replica has not seen the registration")
                            .isZero();

                    assertThat(userContacts.find(student)).isPresent();
                    assertThat(userContacts.findAll(java.util.List.of(student)))
                            .containsOnlyKeys(student);
                    assertThat(workshopSummaries.find(workshop.id()))
                            .get()
                            .extracting(summary -> summary.title())
                            .isEqualTo("Just rescheduled");
                    assertThat(attendees.holdingSeatsFor(workshop.id()))
                            .extracting(attendee -> attendee.userId())
                            .containsExactly(student);
                    assertThat(attendees.find(RegistrationId.of(registrationId))).isPresent();
                });
    }
}
