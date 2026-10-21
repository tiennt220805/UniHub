package com.unihub.payment.infrastructure.out.client;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;

/**
 * Stripe takes an amount in the currency's smallest unit. {@code payments.currency} defaults to
 * VND, which Stripe treats as zero-decimal: multiplying by 100 would charge a hundred times too
 * much. Anything this table does not know is refused rather than guessed.
 */
final class StripeAmounts {
    private static final Set<String> ZERO_DECIMAL =
            Set.of(
                    "BIF", "CLP", "DJF", "GNF", "JPY", "KMF", "KRW", "MGA", "PYG", "RWF", "UGX",
                    "VND", "VUV", "XAF", "XOF", "XPF");
    private static final Set<String> THREE_DECIMAL =
            Set.of("BHD", "IQD", "JOD", "KWD", "LYD", "OMR", "TND");

    private StripeAmounts() {}

    static long minorUnits(BigDecimal amount, String currency) {
        var code = currency.toUpperCase(Locale.ROOT);
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("the amount must be positive");
        }
        if (THREE_DECIMAL.contains(code)) {
            throw new IllegalArgumentException(
                    "three-decimal currencies are not supported by the Stripe adapter: " + code);
        }
        var decimals = ZERO_DECIMAL.contains(code) ? 0 : 2;
        try {
            return amount.movePointRight(decimals).longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "the amount " + amount + " has more decimals than " + code + " allows", e);
        }
    }
}
