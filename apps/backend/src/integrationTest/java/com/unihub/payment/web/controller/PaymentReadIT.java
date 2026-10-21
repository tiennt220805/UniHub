package com.unihub.payment.web.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.shared.security.TestJwts;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/**
 * {@code getPayment} end to end (M-19, M-22's "read right after the write"): the owner of the
 * registration sees the payment's current state, which is the source of truth the charge's snapshot
 * defers to; another student gets 403, an unknown id 404, no token 401.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentReadIT extends PaymentWebhookITSupport {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ResponseEntity<String> get(UUID payment, UUID asUser) {
        var headers = new HttpHeaders();
        if (asUser != null) {
            headers.setBearerAuth(TestJwts.valid(asUser));
        }
        return http.exchange(
                "/api/v1/payments/" + payment,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);
    }

    @Test
    @DisplayName(
            "the owner polls a PENDING payment, then sees it SUCCEEDED once the webhook resolved it")
    void owner_polls_untilResolved() throws Exception {
        var pending = aPendingPayment();

        var before = get(pending.payment(), pending.student().value());
        var body = eventBody("payment_intent.succeeded", pending.gatewayId());
        post(body, sign(body));
        var after = get(pending.payment(), pending.student().value());

        assertThat(before.getStatusCode().value()).isEqualTo(200);
        var first = JSON.readTree(before.getBody());
        assertThat(first.get("status").asText()).isEqualTo("PENDING");
        assertThat(first.get("resolvedAt").isNull()).isTrue();
        assertThat(first.get("gatewayTransactionId").asText()).isEqualTo(pending.gatewayId());
        assertThat(first.get("registrationId").asText())
                .isEqualTo(pending.registration().toString());
        assertThat(first.get("amount").decimalValue()).isEqualByComparingTo("100000");
        var second = JSON.readTree(after.getBody());
        assertThat(second.get("status").asText())
                .as("a read right after the write sees it: it comes from the Master")
                .isEqualTo("SUCCEEDED");
        assertThat(second.hasNonNull("resolvedAt")).isTrue();
    }

    @Test
    @DisplayName("another student gets 403 and learns nothing of the payment")
    void stranger_is403() throws Exception {
        var pending = aPendingPayment();
        var stranger = fixtures.aStudent();

        var response = get(pending.payment(), stranger.value());

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).doesNotContain(pending.gatewayId());
    }

    @Test
    @DisplayName("an unknown payment is 404 and no token is 401")
    void unknown_is404_noToken_is401() {
        var student = fixtures.aStudent();

        assertThat(get(UUID.randomUUID(), student.value()).getStatusCode().value()).isEqualTo(404);
        assertThat(get(UUID.randomUUID(), null).getStatusCode().value()).isEqualTo(401);
    }
}
