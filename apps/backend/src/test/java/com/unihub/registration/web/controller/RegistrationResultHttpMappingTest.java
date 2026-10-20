package com.unihub.registration.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.unihub.registration.application.port.in.ReserveSeatUseCase;
import com.unihub.registration.application.result.ReserveSeatResult;
import com.unihub.shared.application.port.out.IdempotencyKeyInUseException;
import com.unihub.shared.application.port.out.IdempotencyKeyReusedException;
import com.unihub.shared.application.port.out.IdempotencyScope;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.infrastructure.security.SecurityConfig;
import com.unihub.shared.security.TestJwts;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * AC1.6 / AC8.1: every Result variant, and each idempotency exception, has exactly one HTTP mapping
 * — status and problem {@code type} — fixed here, because a replay is only the identical response
 * while that mapping is deterministic. A guard fails the build when a new variant appears without a
 * row.
 */
@WebMvcTest(RegistrationController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "unihub.security.jwt.secret=" + TestJwts.SECRET)
class RegistrationResultHttpMappingTest {
    private static final Instant AT = Instant.parse("2026-12-01T09:00:00Z");

    private record Mapping(
            Class<?> variant, Supplier<ReserveSeatResult> result, int status, String type) {}

    private static final List<Mapping> RESULTS =
            List.of(
                    new Mapping(
                            ReserveSeatResult.Reserved.class,
                            () ->
                                    new ReserveSeatResult.Reserved(
                                            RegistrationId.newId(), AT.plusSeconds(900), AT, AT),
                            201,
                            null),
                    new Mapping(
                            ReserveSeatResult.Confirmed.class,
                            () -> new ReserveSeatResult.Confirmed(RegistrationId.newId(), AT, AT),
                            201,
                            null),
                    new Mapping(
                            ReserveSeatResult.SoldOut.class,
                            ReserveSeatResult.SoldOut::new,
                            409,
                            "urn:unihub:problem:sold-out"),
                    new Mapping(
                            ReserveSeatResult.AlreadyRegistered.class,
                            ReserveSeatResult.AlreadyRegistered::new,
                            409,
                            "urn:unihub:problem:already-registered"),
                    new Mapping(
                            ReserveSeatResult.NotOpenForRegistration.class,
                            ReserveSeatResult.NotOpenForRegistration::new,
                            409,
                            "urn:unihub:problem:registration-not-open"));

    @Autowired private MockMvc mockMvc;

    @MockBean private ReserveSeatUseCase reserveSeat;

    private org.springframework.test.web.servlet.MvcResult send() throws Exception {
        return mockMvc.perform(
                        post("/api/v1/registrations")
                                .header(
                                        "Authorization",
                                        "Bearer "
                                                + TestJwts.valid(
                                                        UUID.randomUUID(), "REGISTRATION_CREATE"))
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"workshopId\":\"" + UUID.randomUUID() + "\"}"))
                .andReturn();
    }

    private static String typeOf(String body) {
        var marker = "\"type\":\"";
        var start = body.indexOf(marker);
        return start < 0
                ? null
                : body.substring(
                        start + marker.length(), body.indexOf('"', start + marker.length()));
    }

    @Test
    @DisplayName("every Result variant maps to its fixed status and problem type")
    void everyVariant_hasItsMapping() throws Exception {
        for (var mapping : RESULTS) {
            when(reserveSeat.reserve(any())).thenReturn(mapping.result().get());

            var response = send().getResponse();

            assertThat(response.getStatus())
                    .as(mapping.variant().getSimpleName())
                    .isEqualTo(mapping.status());
            assertThat(typeOf(response.getContentAsString()))
                    .as(mapping.variant().getSimpleName())
                    .isEqualTo(mapping.type());
        }
    }

    @Test
    @DisplayName("the idempotency refusals map to 409 in-use and 422 reused, each with its type")
    void idempotencyRefusals() throws Exception {
        // doThrow, not when(): re-stubbing a mock that already throws would throw inside when().
        doThrow(new IdempotencyKeyInUseException(IdempotencyScope.REGISTRATION_CREATE))
                .when(reserveSeat)
                .reserve(any());
        var inUse = send().getResponse();
        assertThat(inUse.getStatus()).isEqualTo(409);
        assertThat(typeOf(inUse.getContentAsString()))
                .isEqualTo("urn:unihub:problem:idempotency-key-in-use");

        doThrow(new IdempotencyKeyReusedException(IdempotencyScope.REGISTRATION_CREATE))
                .when(reserveSeat)
                .reserve(any());
        var reused = send().getResponse();
        assertThat(reused.getStatus()).isEqualTo(422);
        assertThat(typeOf(reused.getContentAsString()))
                .isEqualTo("urn:unihub:problem:idempotency-key-reused");
    }

    @Test
    @DisplayName("guard: a Result variant without a mapping row here fails the build")
    void noVariantIsUnmapped() {
        var mapped = RESULTS.stream().map(Mapping::variant).toList();

        assertThat(Arrays.asList(ReserveSeatResult.class.getPermittedSubclasses()))
                .containsExactlyInAnyOrderElementsOf(mapped);
    }
}
