package com.unihub.payment.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.unihub.payment.application.port.in.ChargePaymentUseCase;
import com.unihub.payment.application.port.in.GetPaymentUseCase;
import com.unihub.payment.application.query.GetPaymentQuery;
import com.unihub.payment.application.result.PaymentResult;
import com.unihub.payment.domain.exception.NotRegistrationOwnerException;
import com.unihub.payment.domain.exception.PaymentNotFoundException;
import com.unihub.shared.domain.id.PaymentId;
import com.unihub.shared.domain.id.RegistrationId;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web slice for {@code getPayment}: any authenticated caller may ask, ownership is the use case's.
 */
@WebMvcTest(PaymentController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "unihub.security.jwt.secret=" + TestJwts.SECRET)
class PaymentReadWebTest {
    private static final UUID STUDENT = UUID.randomUUID();
    private static final UUID PAYMENT = UUID.randomUUID();
    private static final Instant AT = Instant.parse("2026-12-01T09:00:00Z");

    @Autowired private MockMvc mockMvc;

    @MockBean private ChargePaymentUseCase chargePayment;
    @MockBean private GetPaymentUseCase getPayment;

    private static String token() {
        return "Bearer " + TestJwts.valid(STUDENT);
    }

    @Test
    @DisplayName("a PENDING payment is 200 with no resolvedAt; the caller is the token's subject")
    void pending_is200() throws Exception {
        when(getPayment.get(any()))
                .thenReturn(
                        new PaymentResult(
                                PAYMENT,
                                UUID.randomUUID(),
                                new BigDecimal("150000.00"),
                                "VND",
                                "STRIPE",
                                "pi_1",
                                "PENDING",
                                AT,
                                null));

        mockMvc.perform(get("/api/v1/payments/" + PAYMENT).header("Authorization", token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(PAYMENT.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.amount").value(150000.0))
                .andExpect(jsonPath("$.resolvedAt").value((Object) null));

        var query = ArgumentCaptor.forClass(GetPaymentQuery.class);
        verify(getPayment).get(query.capture());
        assertThat(query.getValue().caller().value()).isEqualTo(STUDENT);
        assertThat(query.getValue().paymentId()).isEqualTo(PaymentId.of(PAYMENT));
    }

    @Test
    @DisplayName("every one of the six statuses can be shown")
    void everyStatus_isShown() throws Exception {
        for (var status :
                new String[] {"SUCCEEDED", "FAILED", "CANCELLED", "REFUND_REQUIRED", "REFUNDED"}) {
            when(getPayment.get(any()))
                    .thenReturn(
                            new PaymentResult(
                                    PAYMENT,
                                    UUID.randomUUID(),
                                    new BigDecimal("1000"),
                                    "VND",
                                    "STRIPE",
                                    "pi_1",
                                    status,
                                    AT,
                                    AT.plusSeconds(1)));

            mockMvc.perform(get("/api/v1/payments/" + PAYMENT).header("Authorization", token()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(status))
                    .andExpect(jsonPath("$.resolvedAt").value("2026-12-01T09:00:01Z"));
        }
    }

    @Test
    @DisplayName(
            "a payment of someone else's registration is 403, an unknown one 404, no token 401")
    void failures() throws Exception {
        // doThrow, not when(): re-stubbing a mock that already throws would throw inside when().
        doThrow(new NotRegistrationOwnerException(RegistrationId.newId()))
                .when(getPayment)
                .get(any());
        mockMvc.perform(get("/api/v1/payments/" + PAYMENT).header("Authorization", token()))
                .andExpect(status().isForbidden());

        doThrow(new PaymentNotFoundException(PaymentId.of(PAYMENT))).when(getPayment).get(any());
        mockMvc.perform(get("/api/v1/payments/" + PAYMENT).header("Authorization", token()))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/payments/" + PAYMENT)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/payments/not-a-uuid").header("Authorization", token()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("without a token it reaches nothing")
    void unauthenticated_reachesNothing() throws Exception {
        mockMvc.perform(get("/api/v1/payments/" + PAYMENT)).andExpect(status().isUnauthorized());

        verifyNoInteractions(getPayment);
    }
}
