package com.unihub.payment.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.unihub.payment.application.command.ChargePaymentCommand;
import com.unihub.payment.application.port.in.ChargePaymentUseCase;
import com.unihub.payment.application.port.in.GetPaymentUseCase;
import com.unihub.payment.application.result.ChargePaymentResult;
import com.unihub.shared.infrastructure.security.SecurityConfig;
import com.unihub.shared.security.TestJwts;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Web slice for {@code chargePayment}: authorization is by permission and decided before the use
 * case; the request shape is 400 and never reaches it; the caller comes from the token, never from
 * the body. The use case is mocked, no container.
 */
@WebMvcTest(PaymentController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "unihub.security.jwt.secret=" + TestJwts.SECRET)
class ChargePaymentWebTest {
    private static final UUID STUDENT = UUID.randomUUID();
    private static final UUID REGISTRATION = UUID.randomUUID();
    private static final UUID KEY = UUID.randomUUID();
    private static final Instant AT = Instant.parse("2026-12-01T09:00:00Z");
    private static final String BODY =
            "{\"registrationId\":\"" + REGISTRATION + "\",\"paymentMethodToken\":\"pm_card_visa\"}";

    @Autowired private MockMvc mockMvc;

    @MockBean private ChargePaymentUseCase chargePayment;
    @MockBean private GetPaymentUseCase getPayment;

    private static MockHttpServletRequestBuilder request(String token, String key, String json) {
        var builder = post("/api/v1/payments/charge").contentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (key != null) {
            builder.header("Idempotency-Key", key);
        }
        return builder.content(json);
    }

    private static String student() {
        return TestJwts.valid(STUDENT, "REGISTRATION_CREATE");
    }

    @Test
    @DisplayName("a processed charge is 201 with the payment as the use case snapshot it")
    void processed_is201() throws Exception {
        var paymentId = UUID.randomUUID();
        when(chargePayment.charge(any()))
                .thenReturn(
                        new ChargePaymentResult.Processed(
                                paymentId,
                                REGISTRATION,
                                new BigDecimal("150000.00"),
                                "VND",
                                "STRIPE",
                                "pi_1",
                                ChargePaymentResult.Status.SUCCEEDED,
                                AT,
                                AT.plusSeconds(2)));

        mockMvc.perform(request(student(), KEY.toString(), BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(paymentId.toString()))
                .andExpect(jsonPath("$.registrationId").value(REGISTRATION.toString()))
                .andExpect(jsonPath("$.amount").value(150000.0))
                .andExpect(jsonPath("$.currency").value("VND"))
                .andExpect(jsonPath("$.gateway").value("STRIPE"))
                .andExpect(jsonPath("$.gatewayTransactionId").value("pi_1"))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.initiatedAt").value("2026-12-01T09:00:00Z"))
                .andExpect(jsonPath("$.resolvedAt").value("2026-12-01T09:00:02Z"));
    }

    @Test
    @DisplayName("a PENDING snapshot has no resolvedAt, and an absent gateway id is null")
    void pending_hasNoResolvedAt() throws Exception {
        when(chargePayment.charge(any()))
                .thenReturn(
                        new ChargePaymentResult.Processed(
                                UUID.randomUUID(),
                                REGISTRATION,
                                new BigDecimal("1000"),
                                "VND",
                                "STRIPE",
                                null,
                                ChargePaymentResult.Status.PENDING,
                                AT,
                                null));

        mockMvc.perform(request(student(), KEY.toString(), BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.resolvedAt").value((Object) null))
                .andExpect(jsonPath("$.gatewayTransactionId").value((Object) null));
    }

    @Test
    @DisplayName(
            "the command carries the caller from the token, the key, and a fingerprint of the body")
    void command_comesFromTheTokenAndHeader() throws Exception {
        when(chargePayment.charge(any())).thenReturn(new ChargePaymentResult.PaymentInProgress());

        mockMvc.perform(
                request(
                        student(),
                        KEY.toString(),
                        "{\"registrationId\":\""
                                + REGISTRATION
                                + "\",\"paymentMethodToken\":\"pm_card_visa\","
                                + "\"userId\":\""
                                + UUID.randomUUID()
                                + "\"}"));

        var command = ArgumentCaptor.forClass(ChargePaymentCommand.class);
        verify(chargePayment).charge(command.capture());
        assertThat(command.getValue().actor().userId().value())
                .as("an actor in the body is ignored")
                .isEqualTo(STUDENT);
        assertThat(command.getValue().registrationId().value()).isEqualTo(REGISTRATION);
        assertThat(command.getValue().paymentMethodToken()).isEqualTo("pm_card_visa");
        assertThat(command.getValue().idempotencyKey()).isEqualTo(KEY.toString());
        assertThat(command.getValue().requestFingerprint()).hasSize(64);
    }

    @Test
    @DisplayName(
            "without a token it is 401; without the permission 403; neither reaches the use case")
    void authorization() throws Exception {
        mockMvc.perform(request(null, KEY.toString(), BODY)).andExpect(status().isUnauthorized());
        mockMvc.perform(request(TestJwts.valid(STUDENT, "WORKSHOP_CREATE"), KEY.toString(), BODY))
                .andExpect(status().isForbidden());

        verifyNoInteractions(chargePayment);
    }

    @Test
    @DisplayName("a malformed request is 400 and never reaches the use case")
    void badRequests_are400() throws Exception {
        var bad =
                new String[] {
                    "{}",
                    "{\"registrationId\":\"" + REGISTRATION + "\"}",
                    "{\"registrationId\":\"" + REGISTRATION + "\",\"paymentMethodToken\":\"\"}",
                    "{\"registrationId\":\"not-a-uuid\",\"paymentMethodToken\":\"pm_x\"}"
                };
        for (var body : bad) {
            mockMvc.perform(request(student(), KEY.toString(), body))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(request(student(), null, BODY)).andExpect(status().isBadRequest());
        mockMvc.perform(request(student(), "not-a-uuid", BODY)).andExpect(status().isBadRequest());

        verifyNoInteractions(chargePayment);
    }
}
