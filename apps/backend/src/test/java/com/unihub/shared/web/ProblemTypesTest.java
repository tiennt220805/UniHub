package com.unihub.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.registration.domain.exception.AlreadyRegisteredException;
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
