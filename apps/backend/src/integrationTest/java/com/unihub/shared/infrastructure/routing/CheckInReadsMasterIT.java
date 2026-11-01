package com.unihub.shared.infrastructure.routing;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aPublishedWorkshop;
import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.registration.RegistrationFixtures;
import com.unihub.registration.api.TicketLookup;
import com.unihub.shared.infrastructure.ReplicaIntegrationTestSupport;
import com.unihub.shared.security.TestJwts;
import com.unihub.workshop.api.WorkshopSummaries;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import java.util.List;
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
 * The reads that decide whether a scan counts go to the Master (Phase 5 checklist D51, task 3.2;
 * backend-coding-standards.md §20): a registration confirmed a moment ago, or a workshop cancelled
 * a moment ago, must be seen as such even while the Replica is behind. Replay is paused so the
 * Replica really lags, and a control read proves it.
 */
@SpringBootTest
class CheckInReadsMasterIT extends ReplicaIntegrationTestSupport {

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

    @Autowired private TicketLookup tickets;
    @Autowired private WorkshopSummaries workshopSummaries;
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
            "a ticket and a workshop written a moment ago are found while the Replica is behind")
    void freshWrites_areReadFromTheMaster() throws Exception {
        awaitReplicaCaughtUp();

        withReplayPaused(
                () -> {
                    var student = fixtures.aStudent();
                    var workshop = aPublishedWorkshop().withTitle("Just cancelled").build();
                    new TransactionTemplate(transactions)
                            .executeWithoutResult(s -> workshops.save(workshop));
                    fixtures.track(workshop.id());
                    var registrationId = UUID.randomUUID();
                    var code = "FRESH" + UUID.randomUUID().toString().replace("-", "");
                    jdbc.update(
                            "INSERT INTO registrations (id, workshop_id, user_id, ticket_type,"
                                    + " status, ticket_code, qr_payload) VALUES (?, ?, ?, 'FREE',"
                                    + " 'CONFIRMED', ?, 'payload')",
                            registrationId,
                            workshop.id().value(),
                            student.value(),
                            code);

                    assertThat(rowsOnReplica("registrations", registrationId))
                            .as("control: the Replica has not seen the registration")
                            .isZero();
                    assertThat(rowsOnReplica("workshops", workshop.id().value()))
                            .as("control: the Replica has not seen the workshop")
                            .isZero();

                    assertThat(tickets.findByCodes(List.of(code))).containsOnlyKeys(code);
                    assertThat(workshopSummaries.find(workshop.id())).isPresent();
                });
    }
}
