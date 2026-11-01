package com.unihub.registration.application.usecase;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aPublishedWorkshop;
import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.registration.RegistrationFixtures;
import com.unihub.registration.api.TicketLookup;
import com.unihub.registration.api.TicketRegistration;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.security.TestJwts;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The read Check-in makes to turn the ticket codes of a sync batch into registrations (Phase 5
 * checklist D51, task 3.2): one query for the whole batch, the registration's status folded into
 * "is it CONFIRMED", a code nobody holds simply absent.
 */
@SpringBootTest
class RegistrationTicketLookupIT extends IntegrationTestSupport {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> TestJwts.SECRET);
    }

    @Autowired private TicketLookup tickets;
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

    private UUID registration(WorkshopId workshop, String status, String ticketCode) {
        var id = UUID.randomUUID();
        var held = status.equals("RESERVED");
        jdbc.update(
                "INSERT INTO registrations (id, workshop_id, user_id, ticket_type, status,"
                        + " hold_expires_at, ticket_code, qr_payload) VALUES (?, ?, ?, ?, ?,"
                        + " CASE WHEN ? THEN now() + interval '10 minutes' END, ?, ?)",
                id,
                workshop.value(),
                fixtures.aStudent().value(),
                held ? "PAID" : "FREE",
                status,
                held,
                ticketCode,
                ticketCode == null ? null : "payload-" + ticketCode);
        return id;
    }

    private static String code() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 26).toUpperCase();
    }

    @Test
    @DisplayName("a batch of codes is answered with the registration and workshop of each")
    void findsEveryCodeOfTheBatch() {
        var workshopA = aWorkshop();
        var workshopB = aWorkshop();
        var codeA = code();
        var codeB = code();
        var idA = registration(workshopA, "CONFIRMED", codeA);
        var idB = registration(workshopB, "CONFIRMED", codeB);

        var found = tickets.findByCodes(List.of(codeA, codeB));

        assertThat(found)
                .containsOnlyKeys(codeA, codeB)
                .containsEntry(
                        codeA, new TicketRegistration(RegistrationId.of(idA), workshopA, true))
                .containsEntry(
                        codeB, new TicketRegistration(RegistrationId.of(idB), workshopB, true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"RESERVED", "EXPIRED", "CANCELLED"})
    @DisplayName("a registration that is not CONFIRMED is found, and reported as not confirmed")
    void unconfirmedStatuses_areNotConfirmed(String status) {
        var workshop = aWorkshop();
        var code = code();
        registration(workshop, status, code);

        assertThat(tickets.findByCodes(List.of(code)).get(code).confirmed()).isFalse();
    }

    @Test
    @DisplayName("a code nobody holds is left out of the answer, not an error")
    void unknownCode_isAbsent() {
        var workshop = aWorkshop();
        var known = code();
        registration(workshop, "CONFIRMED", known);

        var found = tickets.findByCodes(List.of(known, code(), "not-even-a-code"));

        assertThat(found).containsOnlyKeys(known);
    }

    @Test
    @DisplayName("an empty batch asks nothing and answers nothing")
    void emptyBatch_isEmpty() {
        assertThat(tickets.findByCodes(List.of())).isEmpty();
    }

    @Test
    @DisplayName("a registration confirmed before Phase 5 has no code and is never matched")
    void registrationWithoutTicket_isNeverMatched() {
        var workshop = aWorkshop();
        registration(workshop, "CONFIRMED", null);

        assertThat(tickets.findByCodes(List.of(code()))).isEmpty();
    }

    @Test
    @DisplayName("the same code listed twice is answered once")
    void repeatedCode_isAnsweredOnce() {
        var workshop = aWorkshop();
        var code = code();
        registration(workshop, "CONFIRMED", code);

        assertThat(tickets.findByCodes(List.of(code, code))).containsOnlyKeys(code);
    }
}
