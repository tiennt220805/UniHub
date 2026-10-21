package com.unihub.payment.web.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * S-17 / M-79 (D13, AC13.1): a forged payment webhook could mark a payment successful when nobody
 * paid, so every refusal is checked against the payment it tried to move: still PENDING, no {@code
 * charge.succeeded} row. HTTP all the way, with no token.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentWebhookSignatureIT extends PaymentWebhookITSupport {

    private void assertUntouched(Pending pending) {
        assertThat(paymentStatus(pending.payment())).isEqualTo("PENDING");
        assertThat(fixtures.paymentOutboxRows(pending.registration())).isZero();
    }

    @Test
    @DisplayName("a signature made with the wrong secret is 400 and the payment is untouched")
    void invalidSignature_is400_nothingChanged() {
        var pending = aPendingPayment();
        var body = eventBody("payment_intent.succeeded", pending.gatewayId());

        var response = post(body, signAt(body, "a-forgers-guess", Instant.now()));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).doesNotContain(pending.gatewayId());
        assertUntouched(pending);
    }

    @Test
    @DisplayName("a request with no signature header is 400 and the payment is untouched")
    void missingHeader_is400() {
        var pending = aPendingPayment();

        var response = post(eventBody("payment_intent.succeeded", pending.gatewayId()), null);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertUntouched(pending);
    }

    @Test
    @DisplayName("a body changed after signing is 400, even when only the transaction id differs")
    void bodyAlteredAfterSigning_is400() {
        var pending = aPendingPayment();
        var signedBody = eventBody("payment_intent.payment_failed", "pi_someone_elses");
        var header = sign(signedBody);
        var forged =
                signedBody
                        .replace("payment_failed", "succeeded")
                        .replace("pi_someone_elses", pending.gatewayId());

        var response = post(forged, header);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertUntouched(pending);
    }

    @Test
    @DisplayName("a genuine request captured and replayed after the tolerance is 400")
    void staleSignature_is400() {
        var pending = aPendingPayment();
        var body = eventBody("payment_intent.succeeded", pending.gatewayId());

        var response =
                post(body, signAt(body, SECRET, Instant.now().minus(Duration.ofMinutes(10))));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertUntouched(pending);
    }

    @Test
    @DisplayName(
            "a correctly signed webhook needs no token and is processed: 200, SUCCEEDED, one outbox row")
    void validSignature_isProcessed() {
        var pending = aPendingPayment();
        var body = eventBody("payment_intent.succeeded", pending.gatewayId());

        var response = post(body, sign(body));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(paymentStatus(pending.payment())).isEqualTo("SUCCEEDED");
        assertThat(fixtures.paymentOutboxRows(pending.registration())).isEqualTo(1);
    }
}
