package com.unihub.payment.infrastructure.out.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.net.Webhook;
import com.unihub.payment.application.port.out.GatewayPaymentEvent;
import com.unihub.payment.domain.exception.InvalidWebhookSignatureException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S-17 (D13, AC13.1) at the unit level: the signature is checked over the body exactly as received,
 * before anything is read, and a webhook that does not verify is refused whatever it says.
 */
class StripeWebhookVerifierTest {
    private static final String SECRET = "webhook-secret-for-tests";
    private static final Instant NOW = Instant.parse("2026-12-01T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final StripeWebhookVerifier verifier =
            new StripeWebhookVerifier(
                    new StripeWebhookProperties(SECRET, Duration.ofMinutes(5)),
                    new ObjectMapper(),
                    CLOCK);

    private static String body(String type, String intentId) {
        return "{\"id\":\"evt_1\",\"type\":\""
                + type
                + "\",\"data\":{\"object\":{\"id\":\""
                + intentId
                + "\",\"object\":\"payment_intent\"}}}";
    }

    private static String signed(String payload, String secret, Instant at) throws Exception {
        return Webhook.Signature.generateSignatureHeader(payload, secret, at.getEpochSecond());
    }

    @Test
    @DisplayName("a correctly signed payment_intent.succeeded is the transaction and its outcome")
    void validSignature_isRead() throws Exception {
        var payload = body("payment_intent.succeeded", "pi_123");

        var event = verifier.verify(payload, signed(payload, SECRET, NOW));

        assertThat(event)
                .contains(new GatewayPaymentEvent("pi_123", GatewayPaymentEvent.Outcome.SUCCEEDED));
    }

    @Test
    @DisplayName("failed and canceled intents map to their outcomes")
    void otherOutcomes() throws Exception {
        var failed = body("payment_intent.payment_failed", "pi_1");
        var canceled = body("payment_intent.canceled", "pi_2");

        assertThat(verifier.verify(failed, signed(failed, SECRET, NOW)))
                .contains(new GatewayPaymentEvent("pi_1", GatewayPaymentEvent.Outcome.FAILED));
        assertThat(verifier.verify(canceled, signed(canceled, SECRET, NOW)))
                .contains(new GatewayPaymentEvent("pi_2", GatewayPaymentEvent.Outcome.CANCELLED));
    }

    @Test
    @DisplayName("a signature made with another secret is refused")
    void wrongSecret_isRefused() throws Exception {
        var payload = body("payment_intent.succeeded", "pi_123");

        assertThatThrownBy(() -> verifier.verify(payload, signed(payload, "another-secret", NOW)))
                .isInstanceOf(InvalidWebhookSignatureException.class);
    }

    @Test
    @DisplayName("a body altered after signing is refused, by one byte or by whitespace")
    void alteredBody_isRefused() throws Exception {
        var payload = body("payment_intent.payment_failed", "pi_123");
        var header = signed(payload, SECRET, NOW);

        assertThatThrownBy(() -> verifier.verify(payload.replace("pi_123", "pi_124"), header))
                .isInstanceOf(InvalidWebhookSignatureException.class);
        assertThatThrownBy(() -> verifier.verify(payload + " ", header))
                .isInstanceOf(InvalidWebhookSignatureException.class);
        assertThatThrownBy(() -> verifier.verify(payload.replace(":", ": "), header))
                .as("a re-serialized body is a different body")
                .isInstanceOf(InvalidWebhookSignatureException.class);
    }

    @Test
    @DisplayName("a missing, blank or garbage header is refused")
    void badHeader_isRefused() {
        var payload = body("payment_intent.succeeded", "pi_123");

        assertThatThrownBy(() -> verifier.verify(payload, null))
                .isInstanceOf(InvalidWebhookSignatureException.class);
        assertThatThrownBy(() -> verifier.verify(payload, " "))
                .isInstanceOf(InvalidWebhookSignatureException.class);
        assertThatThrownBy(() -> verifier.verify(payload, "t=abc,v1=zz"))
                .isInstanceOf(InvalidWebhookSignatureException.class);
        assertThatThrownBy(() -> verifier.verify(payload, "not a signature"))
                .isInstanceOf(InvalidWebhookSignatureException.class);
    }

    @Test
    @DisplayName(
            "a captured request replayed outside the tolerance is refused; just inside it is accepted")
    void tolerance_isStrict() throws Exception {
        var payload = body("payment_intent.succeeded", "pi_123");

        assertThatThrownBy(
                        () ->
                                verifier.verify(
                                        payload,
                                        signed(payload, SECRET, NOW.minus(Duration.ofMinutes(6)))))
                .isInstanceOf(InvalidWebhookSignatureException.class);
        assertThat(
                        verifier.verify(
                                payload, signed(payload, SECRET, NOW.minus(Duration.ofMinutes(4)))))
                .isPresent();
    }

    @Test
    @DisplayName("with no secret configured every webhook is refused, whatever it was signed with")
    void noSecret_refusesEverything() throws Exception {
        var closed =
                new StripeWebhookVerifier(
                        new StripeWebhookProperties(null, null), new ObjectMapper(), CLOCK);
        var payload = body("payment_intent.succeeded", "pi_123");

        assertThatThrownBy(() -> closed.verify(payload, signed(payload, "any-key-at-all", NOW)))
                .isInstanceOf(InvalidWebhookSignatureException.class);
        assertThatThrownBy(() -> closed.verify(payload, signed(payload, SECRET, NOW)))
                .isInstanceOf(InvalidWebhookSignatureException.class);
    }

    @Test
    @DisplayName("a verified event about anything else is acknowledged and ignored")
    void unrelatedEvents_areIgnored() throws Exception {
        for (var payload :
                new String[] {
                    body("charge.succeeded", "ch_1"),
                    body("customer.created", "cus_1"),
                    "{\"type\":\"payment_intent.succeeded\",\"data\":{\"object\":{\"object\":\"charge\",\"id\":\"ch_1\"}}}",
                    "{\"type\":\"payment_intent.succeeded\",\"data\":{\"object\":{\"object\":\"payment_intent\"}}}",
                    "{}",
                    "not json at all"
                }) {
            assertThat(verifier.verify(payload, signed(payload, SECRET, NOW)))
                    .as(payload)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("the secret never appears in the printed properties")
    void toString_hidesTheSecret() {
        assertThat(new StripeWebhookProperties(SECRET, null).toString()).doesNotContain(SECRET);
    }
}
