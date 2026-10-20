package com.unihub.registration.domain.model;

import static com.unihub.registration.domain.model.RegistrationBuilder.aCancelledRegistration;
import static com.unihub.registration.domain.model.RegistrationBuilder.aConfirmedRegistration;
import static com.unihub.registration.domain.model.RegistrationBuilder.aReservedRegistration;
import static com.unihub.registration.domain.model.RegistrationBuilder.anExpiredRegistration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unihub.registration.domain.exception.InvalidRegistrationStateException;
import com.unihub.shared.domain.Version;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.domain.id.WorkshopId;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/** State transition catalog for {@code registrations.status} (test-design-techniques.md §2). */
class RegistrationTest {
    private static final Instant NOW = Instant.parse("2026-12-01T09:00:00Z");

    @Test
    @DisplayName("a paid registration is RESERVED and its hold ends at now + the hold duration")
    void reserve_holdsTheSeat() {
        var registration =
                Registration.reserve(
                        RegistrationId.newId(),
                        WorkshopId.newId(),
                        UserId.newId(),
                        NOW,
                        Duration.ofMinutes(15));

        assertThat(registration.status()).isEqualTo(RegistrationStatus.RESERVED);
        assertThat(registration.ticketType()).isEqualTo(TicketType.PAID);
        assertThat(registration.holdExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(registration.version()).isEqualTo(Version.initial());
        assertThat(registration.ticketCode()).isNull();
        assertThat(registration.qrPayload()).isNull();
    }

    @ParameterizedTest
    @MethodSource("badHolds")
    @DisplayName("a hold must last a positive time")
    void reserve_rejectsNonPositiveHold(Duration hold) {
        assertThatThrownBy(
                        () ->
                                Registration.reserve(
                                        RegistrationId.newId(),
                                        WorkshopId.newId(),
                                        UserId.newId(),
                                        NOW,
                                        hold))
                .isInstanceOf(InvalidRegistrationStateException.class);
    }

    static Stream<Duration> badHolds() {
        return Stream.of(null, Duration.ZERO, Duration.ofSeconds(-1));
    }

    @Test
    @DisplayName("a free registration is CONFIRMED at once, with no hold and no ticket yet")
    void confirmFree_isConfirmedWithoutHold() {
        var registration =
                Registration.confirmFree(
                        RegistrationId.newId(), WorkshopId.newId(), UserId.newId());

        assertThat(registration.status()).isEqualTo(RegistrationStatus.CONFIRMED);
        assertThat(registration.isConfirmed()).isTrue();
        assertThat(registration.ticketType()).isEqualTo(TicketType.FREE);
        assertThat(registration.holdExpiresAt()).isNull();
    }

    @Test
    @DisplayName("RESERVED → CONFIRMED clears the hold")
    void confirm_fromReserved() {
        var registration = aReservedRegistration().build();

        registration.confirm();

        assertThat(registration.status()).isEqualTo(RegistrationStatus.CONFIRMED);
        assertThat(registration.holdExpiresAt()).isNull();
    }

    @Test
    @DisplayName("RESERVED → EXPIRED clears the hold")
    void expire_fromReserved() {
        var registration = aReservedRegistration().build();

        registration.expire();

        assertThat(registration.status()).isEqualTo(RegistrationStatus.EXPIRED);
        assertThat(registration.holdExpiresAt()).isNull();
    }

    @Test
    @DisplayName("RESERVED → CANCELLED and CONFIRMED → CANCELLED are allowed")
    void cancel_fromReservedOrConfirmed() {
        var reserved = aReservedRegistration().build();
        var confirmed = aConfirmedRegistration().build();

        reserved.cancel();
        confirmed.cancel();

        assertThat(reserved.status()).isEqualTo(RegistrationStatus.CANCELLED);
        assertThat(reserved.holdExpiresAt()).isNull();
        assertThat(confirmed.status()).isEqualTo(RegistrationStatus.CANCELLED);
    }

    static Stream<Arguments> mustReject() {
        Consumer<Registration> confirm = Registration::confirm;
        Consumer<Registration> expire = Registration::expire;
        Consumer<Registration> cancel = Registration::cancel;
        return Stream.of(
                // a lapsed hold must never convert: its seat may already be reassigned
                Arguments.of("EXPIRED → CONFIRMED", anExpiredRegistration(), confirm),
                // a CONFIRMED row has no hold to expire
                Arguments.of("CONFIRMED → EXPIRED", aConfirmedRegistration(), expire),
                Arguments.of("CONFIRMED → CONFIRMED", aConfirmedRegistration(), confirm),
                Arguments.of("CANCELLED → CONFIRMED", aCancelledRegistration(), confirm),
                Arguments.of("CANCELLED → EXPIRED", aCancelledRegistration(), expire),
                Arguments.of("CANCELLED → CANCELLED", aCancelledRegistration(), cancel),
                Arguments.of("EXPIRED → EXPIRED", anExpiredRegistration(), expire),
                Arguments.of("EXPIRED → CANCELLED", anExpiredRegistration(), cancel));
    }

    @ParameterizedTest(name = "{0} is rejected and leaves the aggregate unchanged")
    @MethodSource("mustReject")
    void forbiddenTransition_isRejected(
            String transition, RegistrationBuilder builder, Consumer<Registration> attempt) {
        var registration = builder.build();
        var before = registration.status();

        assertThatThrownBy(() -> attempt.accept(registration))
                .isInstanceOf(InvalidRegistrationStateException.class);
        assertThat(registration.status()).isEqualTo(before);
        assertThat(registration.holdExpiresAt()).isNull();
    }

    @ParameterizedTest
    @EnumSource(
            value = RegistrationStatus.class,
            names = {"CONFIRMED", "CANCELLED", "EXPIRED"})
    @DisplayName("a hold on a non-RESERVED registration is rejected (RESERVED ⇔ hold_expires_at)")
    void restore_holdOnlyWhenReserved(RegistrationStatus status) {
        assertThatThrownBy(
                        () ->
                                aReservedRegistration()
                                        .withStatus(status)
                                        .withHoldExpiresAt(RegistrationBuilder.HOLD_UNTIL)
                                        .build())
                .isInstanceOf(InvalidRegistrationStateException.class);
    }

    @Test
    @DisplayName("a RESERVED registration without a hold is rejected")
    void restore_reservedNeedsHold() {
        assertThatThrownBy(() -> aReservedRegistration().withHoldExpiresAt(null).build())
                .isInstanceOf(InvalidRegistrationStateException.class);
    }

    @Test
    @DisplayName("a free ticket cannot be RESERVED: there is nothing to pay for")
    void restore_freeCannotBeReserved() {
        assertThatThrownBy(() -> aReservedRegistration().withTicketType(TicketType.FREE).build())
                .isInstanceOf(InvalidRegistrationStateException.class);
    }

    @Test
    @DisplayName("only RESERVED and CONFIRMED occupy a seat; CANCELLED and EXPIRED are terminal")
    void statusFlags() {
        assertThat(RegistrationStatus.RESERVED.occupiesSeat()).isTrue();
        assertThat(RegistrationStatus.CONFIRMED.occupiesSeat()).isTrue();
        assertThat(RegistrationStatus.CANCELLED.occupiesSeat()).isFalse();
        assertThat(RegistrationStatus.EXPIRED.occupiesSeat()).isFalse();
        assertThat(RegistrationStatus.CANCELLED.isTerminal()).isTrue();
        assertThat(RegistrationStatus.EXPIRED.isTerminal()).isTrue();
        assertThat(RegistrationStatus.RESERVED.isTerminal()).isFalse();
        assertThat(RegistrationStatus.CONFIRMED.isTerminal()).isFalse();
    }
}
