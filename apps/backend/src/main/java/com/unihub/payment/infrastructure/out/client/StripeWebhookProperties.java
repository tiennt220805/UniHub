package com.unihub.payment.infrastructure.out.client;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code unihub.payment.stripe.webhook.*} (payment.md §7). The signing secret comes from the
 * environment ({@code UNIHUB_PAYMENT_STRIPE_WEBHOOK_SECRET}), never from source (INV-18). With none
 * set every webhook is refused: an endpoint that cannot verify must not accept.
 *
 * @param tolerance how far a signature's timestamp may be from now; a captured request replayed
 *     later than this is refused
 */
@ConfigurationProperties(prefix = "unihub.payment.stripe.webhook")
public record StripeWebhookProperties(String secret, Duration tolerance) {

    public StripeWebhookProperties {
        tolerance = tolerance == null ? Duration.ofMinutes(5) : tolerance;
        if (tolerance.isNegative() || tolerance.isZero()) {
            throw new IllegalArgumentException(
                    "unihub.payment.stripe.webhook.tolerance must be positive");
        }
    }

    /** Never prints the secret. */
    @Override
    public String toString() {
        return "StripeWebhookProperties[tolerance=" + tolerance + "]";
    }
}
