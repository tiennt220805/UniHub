package com.unihub.registration.infrastructure.in.messaging;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aPaidWorkshop;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.registration.HandOffITSupport;
import com.unihub.registration.application.command.ConfirmRegistrationCommand;
import com.unihub.registration.application.command.ExpireSeatHoldCommand;
import com.unihub.registration.application.command.ReserveSeatCommand;
import com.unihub.registration.application.port.in.ConfirmRegistrationUseCase;
import com.unihub.registration.application.port.in.ExpireSeatHoldUseCase;
import com.unihub.registration.application.result.ReserveSeatResult;
import com.unihub.registration.domain.exception.SeatLockUnavailableException;
import com.unihub.shared.domain.OptimisticConflictException;
import com.unihub.shared.domain.id.PaymentId;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.infrastructure.Redeliveries;
import com.unihub.shared.security.Actor;
import com.unihub.shared.web.RequestFingerprint;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

/**
 * INV-11, the concurrent half for the Registration consumers (Phase 4 checklist D41, AC41.1, M-40):
 * the same message handled by two threads at once, through the {@code port/in} the listener calls,
 * with the overlap measured. The sequential half already exists ({@link PaymentConfirmationIT},
 * {@link SeatHoldExpiryIT}: one message, three deliveries, effects counted); a distinct message
 * racing a different one is {@code raceConfirmVsExpire_exactlyOneWins}.
 *
 * <p>A losing call may fail only with what the listener treats as transient, which the broker would
 * retry: the version race ({@link OptimisticConflictException}) or the seat lock ({@link
 * SeatLockUnavailableException}). After any failure the call is made once more, standing for the
 * retry hop, and must change nothing. The assertions count effects.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest
class RegistrationConsumersConcurrentRedeliveryIT extends HandOffITSupport {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int ROUNDS = 5;
    private static final String TICKET_CONFIRMED = "unihub.registration.ticket.confirmed";
    private static final String REJECTED = "unihub.registration.confirmation.rejected";

    @Autowired private ConfirmRegistrationUseCase confirmRegistration;
    @Autowired private ExpireSeatHoldUseCase expireSeatHold;

    private long registrationVersion(UUID registration) {
        return jdbc.queryForObject(
                "SELECT version FROM registrations WHERE id = ?", Long.class, registration);
    }

    @Test
    @DisplayName(
            "payment-confirmation: the same success handled by two threads at once confirms once"
                    + " and announces once")
    void sameSuccess_fromTwoThreads_confirmsOnce() {
        for (int round = 0; round < ROUNDS; round++) {
            var paid = aPaidRegistration();
            var command =
                    new ConfirmRegistrationCommand(
                            PaymentId.of(paid.payment()), RegistrationId.of(paid.registration()));
            var seatsBefore = fixtures.seatsTaken(new WorkshopId(paid.workshop()));
            var versionBefore = registrationVersion(paid.registration());

            var outcome =
                    Redeliveries.concurrently(2, () -> confirmRegistration.confirm(command))
                            .assertOnlyFailuresOf(OptimisticConflictException.class);
            if (outcome.anyFailed()) {
                confirmRegistration.confirm(command); // the retry hop: must be a no-op
            }

            assertThat(registrationStatus(paid.registration()))
                    .as("round " + round)
                    .isEqualTo("CONFIRMED");
            assertThat(outboxRows(paid.registration(), TICKET_CONFIRMED))
                    .as("one ticket.confirmed, round " + round)
                    .isEqualTo(1);
            assertThat(outboxRows(paid.registration(), REJECTED)).isZero();
            assertThat(registrationVersion(paid.registration()))
                    .as("one conditional transition")
                    .isEqualTo(versionBefore + 1);
            assertThat(fixtures.seatsTaken(new WorkshopId(paid.workshop())))
                    .as("the seat is neither given back nor taken again")
                    .isEqualTo(seatsBefore);
        }
    }

    @Test
    @DisplayName(
            "seat-hold-expiry: the same hold.expired handled by two threads at once returns the"
                    + " seat exactly once")
    void sameHoldExpired_fromTwoThreads_returnsTheSeatOnce() {
        for (int round = 0; round < ROUNDS; round++) {
            var workshop = aPaidWorkshop().withCapacity(5).build();
            tx.executeWithoutResult(s -> workshops.save(workshop));
            var workshopId = fixtures.track(workshop.id());
            var held = reserveFor(fixtures.aStudent(), workshopId);
            var kept = reserveFor(fixtures.aStudent(), workshopId);
            assertThat(fixtures.seatsTaken(workshopId)).isEqualTo(2);
            fixtures.expireTheHold(held);
            var command = new ExpireSeatHoldCommand(RegistrationId.of(held), workshopId);

            var outcome =
                    Redeliveries.concurrently(2, () -> expireSeatHold.expire(command))
                            .assertOnlyFailuresOf(
                                    OptimisticConflictException.class,
                                    SeatLockUnavailableException.class);
            if (outcome.anyFailed()) {
                expireSeatHold.expire(command); // the retry hop: must be a no-op
            }

            assertThat(registrationStatus(held)).as("round " + round).isEqualTo("EXPIRED");
            assertThat(fixtures.seatsTaken(workshopId))
                    .as("one seat given back, the other student's kept, round " + round)
                    .isEqualTo(1);
            assertThat(registrationStatus(kept)).isEqualTo("RESERVED");
            assertThat(seatHolders(workshopId)).isEqualTo(1);
        }
    }

    private UUID reserveFor(UserId student, WorkshopId workshop) {
        var result =
                reserveSeat.reserve(
                        new ReserveSeatCommand(
                                new Actor.UserActor(student, Set.of()),
                                workshop,
                                UUID.randomUUID().toString(),
                                RequestFingerprint.of(
                                        JSON, Map.of("workshopId", workshop.value()))));
        assertThat(result).isInstanceOf(ReserveSeatResult.Reserved.class);
        return ((ReserveSeatResult.Reserved) result).id().value();
    }

    private int seatHolders(WorkshopId workshop) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM registrations WHERE workshop_id = ? AND status IN"
                        + " ('RESERVED', 'CONFIRMED')",
                Integer.class,
                workshop.value());
    }
}
