package com.unihub.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.payment.domain.exception.LivePaymentExistsException;
import com.unihub.payment.domain.exception.NotRegistrationOwnerException;
import com.unihub.registration.domain.exception.AlreadyRegisteredException;
import com.unihub.shared.application.port.out.IdempotencyKeyInUseException;
import com.unihub.shared.application.port.out.IdempotencyKeyReusedException;
import com.unihub.shared.application.port.out.IdempotencyScope;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.domain.id.WorkshopId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ProblemTypesTest {

    @Test
    @DisplayName("the type URIs are the ones the contract names (stable, never renamed)")
    void catalog() {
        assertThat(ProblemTypes.SOLD_OUT).hasToString("urn:unihub:problem:sold-out");
        assertThat(ProblemTypes.ALREADY_REGISTERED)
                .hasToString("urn:unihub:problem:already-registered");
        assertThat(ProblemTypes.REGISTRATION_NOT_OPEN)
                .hasToString("urn:unihub:problem:registration-not-open");
        assertThat(ProblemTypes.IDEMPOTENCY_KEY_IN_USE)
                .hasToString("urn:unihub:problem:idempotency-key-in-use");
        assertThat(ProblemTypes.IDEMPOTENCY_KEY_REUSED)
                .hasToString("urn:unihub:problem:idempotency-key-reused");
        assertThat(ProblemTypes.REGISTRATION_NOT_RESERVED)
                .hasToString("urn:unihub:problem:registration-not-reserved");
        assertThat(ProblemTypes.PAYMENT_IN_PROGRESS)
                .hasToString("urn:unihub:problem:payment-in-progress");
    }

    @Test
    @DisplayName(
            "LivePaymentExistsException (the backstop) carries the same type as the Result path")
    void livePaymentBackstop_carriesTheSameType() {
        var exception =
                new LivePaymentExistsException(
                        RegistrationId.newId(), new RuntimeException("index"));

        assertThat(exception.problemType()).isEqualTo(ProblemTypes.PAYMENT_IN_PROGRESS.toString());
        assertThat(exception.kind()).isEqualTo(com.unihub.shared.domain.ProblemKind.CONFLICT);
    }

    @Test
    @DisplayName("a caller who does not own the registration is a 403 with no type")
    void notTheOwner_is403() {
        var response =
                new GlobalProblemHandler()
                        .handleDomain(
                                new NotRegistrationOwnerException(RegistrationId.newId()),
                                new MockHttpServletRequest("POST", "/api/v1/payments/charge"));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    @DisplayName("the idempotency exceptions carry the same types as the constants, 409 and 422")
    void idempotencyExceptions_carryTheirTypes() {
        var inUse = new IdempotencyKeyInUseException(IdempotencyScope.REGISTRATION_CREATE);
        var reused = new IdempotencyKeyReusedException(IdempotencyScope.REGISTRATION_CREATE);

        assertThat(inUse.problemType()).isEqualTo(ProblemTypes.IDEMPOTENCY_KEY_IN_USE.toString());
        assertThat(reused.problemType()).isEqualTo(ProblemTypes.IDEMPOTENCY_KEY_REUSED.toString());
        assertThat(inUse.kind()).isEqualTo(com.unihub.shared.domain.ProblemKind.CONFLICT);
        assertThat(reused.kind()).isEqualTo(com.unihub.shared.domain.ProblemKind.UNPROCESSABLE);
    }

    @Test
    @DisplayName(
            "AlreadyRegisteredException (the backstop) carries the same type as the Result path")
    void backstopException_carriesTheSameType() {
        var exception = new AlreadyRegisteredException(UserId.newId(), WorkshopId.newId());

        assertThat(exception.problemType()).isEqualTo(ProblemTypes.ALREADY_REGISTERED.toString());
    }

    @Test
    @DisplayName("the handler puts a DomainException's type on the problem, 409 for the backstop")
    void handler_usesProblemType() {
        var response =
                new GlobalProblemHandler()
                        .handleDomain(
                                new AlreadyRegisteredException(UserId.newId(), WorkshopId.newId()),
                                new MockHttpServletRequest("POST", "/api/v1/registrations"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().getType()).isEqualTo(ProblemTypes.ALREADY_REGISTERED);
    }

    @Test
    @DisplayName("a DomainException without a type stays about:blank")
    void handler_defaultsToAboutBlank() {
        var response =
                new GlobalProblemHandler()
                        .handleDomain(
                                new com.unihub.shared.domain.OptimisticConflictException(
                                        "WORKSHOP", java.util.UUID.randomUUID()),
                                new MockHttpServletRequest("PATCH", "/api/v1/workshops/x"));

        assertThat(response.getBody().getType().toString()).isEqualTo("about:blank");
    }
}
