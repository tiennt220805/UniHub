package com.unihub.payment.infrastructure.out.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.net.Webhook;
import com.unihub.payment.application.port.out.GatewayPaymentEvent;
import com.unihub.payment.application.port.out.PaymentWebhookVerifier;
import com.unihub.payment.domain.exception.InvalidWebhookSignatureException;
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link PaymentWebhookVerifier} for Stripe (payment.md §7): the {@code Stripe-Signature} header is
 * an HMAC-SHA256 over {@code timestamp.rawBody} with the webhook signing secret, checked with the
 * SDK against the body exactly as received and within the configured tolerance. Only after it holds
 * is the body parsed, and then only for the transaction id and what became of it.
 *
 * <p>Fail-closed: no secret configured means every webhook is refused.
 */
public final class StripeWebhookVerifier implements PaymentWebhookVerifier {
    private static final Logger LOG = LoggerFactory.getLogger(StripeWebhookVerifier.class);

    private final String secret;
    private final long toleranceSeconds;
    private final ObjectMapper json;
    private final Clock clock;

    public StripeWebhookVerifier(
            StripeWebhookProperties properties, ObjectMapper json, Clock clock) {
        this.secret = properties.secret();
        this.toleranceSeconds = properties.tolerance().toSeconds();
        this.json = json;
        this.clock = clock;
        if (secret == null || secret.isBlank()) {
            LOG.warn(
                    "No Stripe webhook secret is configured: every payment webhook will be refused"
                            + " (unihub.payment.stripe.webhook.secret)");
        }
    }

    @Override
    public Optional<GatewayPaymentEvent> verify(String rawBody, String signatureHeader) {
        if (secret == null
                || secret.isBlank()
                || rawBody == null
                || signatureHeader == null
                || signatureHeader.isBlank()) {
            throw new InvalidWebhookSignatureException();
        }
        try {
            if (!Webhook.Signature.verifyHeader(
                    rawBody, signatureHeader, secret, toleranceSeconds, clock)) {
                throw new InvalidWebhookSignatureException();
            }
        } catch (SignatureVerificationException | RuntimeException e) {
            if (e instanceof InvalidWebhookSignatureException refused) {
                throw refused;
            }
            throw new InvalidWebhookSignatureException(e);
        }
        return read(rawBody);
    }

    /** Called only once the signature holds. */
    private Optional<GatewayPaymentEvent> read(String rawBody) {
        JsonNode event;
        try {
            event = json.readTree(rawBody);
        } catch (java.io.IOException malformed) {
            LOG.warn("A correctly signed payment webhook is not JSON; acknowledged and ignored");
            return Optional.empty();
        }
        var outcome = outcomeOf(event.path("type").asText(""));
        var object = event.path("data").path("object");
        var id = object.path("id").asText("");
        if (outcome == null
                || !"payment_intent".equals(object.path("object").asText(""))
                || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new GatewayPaymentEvent(id, outcome));
    }

    private static GatewayPaymentEvent.Outcome outcomeOf(String type) {
        return switch (type) {
            case "payment_intent.succeeded" -> GatewayPaymentEvent.Outcome.SUCCEEDED;
            case "payment_intent.payment_failed" -> GatewayPaymentEvent.Outcome.FAILED;
            case "payment_intent.canceled" -> GatewayPaymentEvent.Outcome.CANCELLED;
            default -> null;
        };
    }
}
