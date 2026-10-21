package com.unihub.payment.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.unihub.payment.application.port.in.ChargePaymentUseCase;
import com.unihub.payment.application.result.ChargePaymentResult;
import com.unihub.payment.domain.exception.LivePaymentExistsException;
import com.unihub.payment.domain.exception.NotRegistrationOwnerException;
import com.unihub.payment.domain.exception.PayableRegistrationNotFoundException;
import com.unihub.payment.domain.exception.PaymentGatewayUnavailableException;
import com.unihub.shared.application.port.out.IdempotencyKeyInUseException;
import com.unihub.shared.application.port.out.IdempotencyKeyReusedException;
import com.unihub.shared.application.port.out.IdempotencyScope;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.infrastructure.security.SecurityConfig;
import com.unihub.shared.security.TestJwts;
import java.math.BigDecimal;
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
 * AC1.6 / AC8.1 for the charge: every Result variant and each failure has exactly one HTTP mapping
 * (status, problem {@code type}, headers), because a replay is only the identical response while
 * that mapping is deterministic. A guard fails the build when a new variant appears without a row.
 */
@WebMvcTest(PaymentController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "unihub.security.jwt.secret=" + TestJwts.SECRET)
class PaymentResultHttpMappingTest {
    private static final Instant AT = Instant.parse("2026-12-01T09:00:00Z");
    private static final RegistrationId REGISTRATION = RegistrationId.newId();

    private record Mapping(
            Class<?> variant, Supplier<ChargePaymentResult> result, int status, String type) {}

    private static ChargePaymentResult.Processed processed(ChargePaymentResult.Status status) {
        return new ChargePaymentResult.Processed(
                UUID.randomUUID(),
                REGISTRATION.value(),
                new BigDecimal("150000.00"),
                "VND",
                "STRIPE",
                "pi_1",
                status,
                AT,
                status == ChargePaymentResult.Status.PENDING ? null : AT.plusSeconds(2));
    }

    private static final List<Mapping> RESULTS =
            List.of(
                    new Mapping(
                            ChargePaymentResult.Processed.class,
                            () -> processed(ChargePaymentResult.Status.SUCCEEDED),
                            201,
                            null),
                    new Mapping(
                            ChargePaymentResult.RegistrationNotReserved.class,
                            ChargePaymentResult.RegistrationNotReserved::new,
                            409,
                            "urn:unihub:problem:registration-not-reserved"),
                    new Mapping(
                            ChargePaymentResult.PaymentInProgress.class,
                            ChargePaymentResult.PaymentInProgress::new,
                            409,
                            "urn:unihub:problem:payment-in-progress"));

    @Autowired private MockMvc mockMvc;

    @MockBean private ChargePaymentUseCase chargePayment;

    private org.springframework.test.web.servlet.MvcResult send() throws Exception {
        return mockMvc.perform(
                        post("/api/v1/payments/charge")
                                .header(
                                        "Authorization",
                                        "Bearer "
                                                + TestJwts.valid(
                                                        UUID.randomUUID(), "REGISTRATION_CREATE"))
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"registrationId\":\""
                                                + REGISTRATION.value()
                                                + "\",\"paymentMethodToken\":\"pm_card_visa\"}"))
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
            when(chargePayment.charge(any())).thenReturn(mapping.result().get());

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
    @DisplayName("each real status of a processed charge is a 201 carrying that status")
    void processed_isAlways201_withItsStatus() throws Exception {
        for (var status :
                new ChargePaymentResult.Status[] {
                    ChargePaymentResult.Status.SUCCEEDED,
                    ChargePaymentResult.Status.FAILED,
                    ChargePaymentResult.Status.PENDING
                }) {
            when(chargePayment.charge(any())).thenReturn(processed(status));

            var response = send().getResponse();

            assertThat(response.getStatus()).as(status.name()).isEqualTo(201);
            assertThat(response.getContentAsString())
                    .as(status.name())
                    .contains("\"status\":\"" + status.name() + "\"");
        }
    }

    @Test
    @DisplayName(
            "the failures map to 403, 404, 503 + Retry-After, and the live-payment backstop to 409")
    void failures() throws Exception {
        doThrow(new NotRegistrationOwnerException(REGISTRATION)).when(chargePayment).charge(any());
        assertThat(send().getResponse().getStatus()).isEqualTo(403);

        doThrow(new PayableRegistrationNotFoundException(REGISTRATION))
                .when(chargePayment)
                .charge(any());
        assertThat(send().getResponse().getStatus()).isEqualTo(404);

        doThrow(new PaymentGatewayUnavailableException("down", null))
                .when(chargePayment)
                .charge(any());
        var unavailable = send().getResponse();
        assertThat(unavailable.getStatus()).isEqualTo(503);
        assertThat(unavailable.getHeader("Retry-After")).isEqualTo("30");

        doThrow(new LivePaymentExistsException(REGISTRATION, new RuntimeException()))
                .when(chargePayment)
                .charge(any());
        var backstop = send().getResponse();
        assertThat(backstop.getStatus()).isEqualTo(409);
        assertThat(typeOf(backstop.getContentAsString()))
                .isEqualTo("urn:unihub:problem:payment-in-progress");
    }

    @Test
    @DisplayName("the idempotency refusals map to 409 in-use and 422 reused, each with its type")
    void idempotencyRefusals() throws Exception {
        doThrow(new IdempotencyKeyInUseException(IdempotencyScope.PAYMENT_CHARGE))
                .when(chargePayment)
                .charge(any());
        var inUse = send().getResponse();
        assertThat(inUse.getStatus()).isEqualTo(409);
        assertThat(typeOf(inUse.getContentAsString()))
                .isEqualTo("urn:unihub:problem:idempotency-key-in-use");

        doThrow(new IdempotencyKeyReusedException(IdempotencyScope.PAYMENT_CHARGE))
                .when(chargePayment)
                .charge(any());
        var reused = send().getResponse();
        assertThat(reused.getStatus()).isEqualTo(422);
        assertThat(typeOf(reused.getContentAsString()))
                .isEqualTo("urn:unihub:problem:idempotency-key-reused");
    }

    @Test
    @DisplayName("guard: a Result variant without a mapping row here fails the build")
    void noVariantIsUnmapped() {
        var mapped = RESULTS.stream().map(Mapping::variant).toList();

        assertThat(Arrays.asList(ChargePaymentResult.class.getPermittedSubclasses()))
                .containsExactlyInAnyOrderElementsOf(mapped);
    }
}
