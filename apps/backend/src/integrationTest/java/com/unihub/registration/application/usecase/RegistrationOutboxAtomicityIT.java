package com.unihub.registration.application.usecase;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aPaidWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aPublishedWorkshop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.registration.RegistrationFixtures;
import com.unihub.registration.api.event.RegistrationConfirmedIntegrationEvent;
import com.unihub.registration.application.command.ReserveSeatCommand;
import com.unihub.registration.application.port.in.ReserveSeatUseCase;
import com.unihub.registration.application.result.ReserveSeatResult;
import com.unihub.shared.application.port.out.EventPublisher;
import com.unihub.shared.application.port.out.IdempotencyStore;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.security.Actor;
import com.unihub.shared.security.TestJwts;
import com.unihub.shared.web.RequestFingerprint;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import com.unihub.workshop.domain.model.Workshop;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M-25 (INV-10, Phase 2 checklist 3.4): the {@code ticket.confirmed} outbox row commits atomically
 * with the registration write: both exist, or neither does. Failures are forced on either side of
 * the outbox insert. Deliberately not {@code @Transactional}: the property is about what happens
 * across the transaction boundary, which a test-managed transaction would hide
 * (test-data-management.md §4).
 */
@SpringBootTest
class RegistrationOutboxAtomicityIT extends IntegrationTestSupport {
    private static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> TestJwts.SECRET);
    }

    @Autowired private ReserveSeatUseCase reserveSeat;
    @Autowired private WorkshopRepository workshops;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate tx;

    /** Spied so a test can make the transaction fail on either side of the outbox insert. */
    @SpyBean private IdempotencyStore idempotency;

    @SpyBean private EventPublisher eventPublisher;

    private RegistrationFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new RegistrationFixtures(jdbc);
        reset(idempotency, eventPublisher);
    }

    @AfterEach
    void cleanUp() {
        reset(idempotency, eventPublisher);
        fixtures.cleanUp();
    }

    /** Not an IllegalStateException: a repository proxy would translate that one. */
    private static final class AfterTheOutboxInsert extends RuntimeException {
        AfterTheOutboxInsert() {
            super("fails after the outbox row was inserted");
        }
    }

    /**
     * Stubbed inside a transaction: both ports are {@code MANDATORY}, and the spy sits behind that
     * proxy, so even the {@code when(...)} call of a stub is refused without one.
     */
    private void failTheKeyCompletion() {
        tx.executeWithoutResult(
                s ->
                        doThrow(new AfterTheOutboxInsert())
                                .when(idempotency)
                                .complete(any(), any(), any(), any(), any()));
    }

    private void failTheOutboxInsert() {
        tx.executeWithoutResult(
                s -> doThrow(new AfterTheOutboxInsert()).when(eventPublisher).publish(any()));
    }

    private WorkshopId saved(Workshop workshop) {
        tx.executeWithoutResult(s -> workshops.save(workshop));
        return fixtures.track(workshop.id());
    }

    private static ReserveSeatCommand command(UserId student, WorkshopId workshop, UUID key) {
        return new ReserveSeatCommand(
                new Actor.UserActor(student, Set.of()),
                workshop,
                key.toString(),
                RequestFingerprint.of(JSON, Map.of("workshopId", workshop.value())));
    }

    private ReserveSeatResult.Confirmed registerForFree(UserId student, WorkshopId workshop) {
        return (ReserveSeatResult.Confirmed)
                reserveSeat.reserve(command(student, workshop, UUID.randomUUID()));
    }

    @Test
    @DisplayName("a free registration commits its ticket.confirmed row with the registration")
    void committed_hasBothRows() throws Exception {
        var workshop = saved(aPublishedWorkshop().withCapacity(5).build());
        var student = fixtures.aStudent();

        var confirmed = registerForFree(student, workshop);

        assertThat(fixtures.registrationRows(workshop)).isEqualTo(1);
        var row =
                jdbc.queryForMap(
                        "SELECT aggregate_type, routing_key, event_type, payload::text AS payload,"
                                + " aggregate_id FROM event_publications WHERE aggregate_id = ?",
                        confirmed.id().value());
        assertThat(row)
                .containsEntry("aggregate_type", "REGISTRATION")
                .containsEntry("routing_key", RegistrationConfirmedIntegrationEvent.ROUTING_KEY)
                .containsEntry("event_type", "RegistrationConfirmedIntegrationEvent");
        JsonNode payload = JSON.readTree((String) row.get("payload"));
        assertThat(payload.get("registrationId").asText())
                .isEqualTo(confirmed.id().value().toString());
        assertThat(payload.get("workshopId").asText()).isEqualTo(workshop.value().toString());
        assertThat(payload.get("userId").asText()).isEqualTo(student.value().toString());
        assertThat(payload.get("schemaVersion").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("a failure after the outbox insert leaves neither the registration nor the row")
    void failureAfterTheInsert_leavesNeither() {
        var workshop = saved(aPublishedWorkshop().withCapacity(5).build());
        var student = fixtures.aStudent();
        failTheKeyCompletion();

        assertThatThrownBy(() -> registerForFree(student, workshop))
                .isInstanceOf(AfterTheOutboxInsert.class);

        assertThat(fixtures.registrationRows(workshop)).isZero();
        assertThat(fixtures.outboxRowsForWorkshop(workshop)).isZero();
        assertThat(outboxRowsAnnouncingRegistrationsOf(student)).isZero();
        assertThat(fixtures.seatsTaken(workshop)).isZero();
        assertThat(fixtures.idempotencyKeys(student)).isZero();
    }

    @Test
    @DisplayName("a failing outbox insert rolls the registration and its seat back")
    void failingInsert_leavesNoRegistration() {
        var workshop = saved(aPublishedWorkshop().withCapacity(5).build());
        var student = fixtures.aStudent();
        failTheOutboxInsert();

        assertThatThrownBy(() -> registerForFree(student, workshop))
                .isInstanceOf(AfterTheOutboxInsert.class);

        assertThat(fixtures.registrationRows(workshop)).isZero();
        assertThat(fixtures.seatsTaken(workshop)).isZero();
        assertThat(fixtures.idempotencyKeys(student)).isZero();
    }

    @Test
    @DisplayName("after a rolled-back attempt, a retry commits exactly one registration and row")
    void retryAfterRollback_commitsOnce() {
        var workshop = saved(aPublishedWorkshop().withCapacity(5).build());
        var student = fixtures.aStudent();
        var key = UUID.randomUUID();
        failTheKeyCompletion();
        assertThatThrownBy(() -> reserveSeat.reserve(command(student, workshop, key)))
                .isInstanceOf(AfterTheOutboxInsert.class);
        reset(idempotency);

        var confirmed =
                (ReserveSeatResult.Confirmed) reserveSeat.reserve(command(student, workshop, key));

        assertThat(fixtures.registrationRows(workshop)).isEqualTo(1);
        assertThat(fixtures.outboxRows(confirmed.id().value())).isEqualTo(1);
    }

    @Test
    @DisplayName("a replay of the same key does not announce the confirmation a second time")
    void replay_addsNoRow() {
        var workshop = saved(aPublishedWorkshop().withCapacity(5).build());
        var student = fixtures.aStudent();
        var key = UUID.randomUUID();

        var first =
                (ReserveSeatResult.Confirmed) reserveSeat.reserve(command(student, workshop, key));
        reserveSeat.reserve(command(student, workshop, key));

        assertThat(fixtures.outboxRows(first.id().value())).isEqualTo(1);
    }

    @Test
    @DisplayName("a paid hold is not a confirmation: it writes no ticket.confirmed row")
    void paidHold_writesNoRow() {
        var workshop = saved(aPaidWorkshop().withCapacity(5).build());
        var student = fixtures.aStudent();

        var result = reserveSeat.reserve(command(student, workshop, UUID.randomUUID()));

        assertThat(result).isInstanceOf(ReserveSeatResult.Reserved.class);
        assertThat(fixtures.registrationRows(workshop)).isEqualTo(1);
        assertThat(fixtures.outboxRowsForWorkshop(workshop)).isZero();
    }

    @Test
    @DisplayName("a refusal (sold out, already registered) writes no row")
    void refusals_writeNoRow() {
        var workshop = saved(aPublishedWorkshop().withCapacity(1).build());
        var first = fixtures.aStudent();
        var second = fixtures.aStudent();
        registerForFree(first, workshop);

        var soldOut = reserveSeat.reserve(command(second, workshop, UUID.randomUUID()));
        var again = reserveSeat.reserve(command(first, workshop, UUID.randomUUID()));

        assertThat(soldOut).isInstanceOf(ReserveSeatResult.SoldOut.class);
        assertThat(again).isInstanceOf(ReserveSeatResult.AlreadyRegistered.class);
        assertThat(fixtures.outboxRowsForWorkshop(workshop)).isEqualTo(1);
    }

    private int outboxRowsAnnouncingRegistrationsOf(UserId student) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM event_publications WHERE aggregate_type = 'REGISTRATION'"
                        + " AND payload ->> 'userId' = ?",
                Integer.class,
                student.value().toString());
    }
}
