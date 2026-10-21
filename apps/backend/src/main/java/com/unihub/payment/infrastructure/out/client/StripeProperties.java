package com.unihub.payment.infrastructure.out.client;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code unihub.payment.stripe.*} (payment.md §3). The secret key comes from the environment at
 * runtime ({@code UNIHUB_PAYMENT_STRIPE_SECRET_KEY}) and is never in source or committed config
 * (INV-18); with no key there is no gateway bean and the application still boots.
 */
@ConfigurationProperties(prefix = "unihub.payment.stripe")
public record StripeProperties(
        String secretKey, String apiBase, Duration connectTimeout, Duration readTimeout) {
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(3);

    public StripeProperties {
        apiBase = apiBase == null || apiBase.isBlank() ? "https://api.stripe.com" : apiBase;
        connectTimeout = connectTimeout == null ? MAX_TIMEOUT : connectTimeout;
        readTimeout = readTimeout == null ? MAX_TIMEOUT : readTimeout;
        requireWithinBudget("connect-timeout", connectTimeout);
        requireWithinBudget("read-timeout", readTimeout);
    }

    private static void requireWithinBudget(String name, Duration value) {
        if (value.isNegative() || value.isZero() || value.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException(
                    "unihub.payment.stripe."
                            + name
                            + " must be between 1ms and 3s (payment.md §3)");
        }
    }

    /** Never prints the key. */
    @Override
    public String toString() {
        return "StripeProperties[apiBase="
                + apiBase
                + ", connectTimeout="
                + connectTimeout
                + ", readTimeout="
                + readTimeout
                + "]";
    }
}
