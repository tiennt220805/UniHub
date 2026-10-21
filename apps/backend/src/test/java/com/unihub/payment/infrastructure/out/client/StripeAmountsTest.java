package com.unihub.payment.infrastructure.out.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StripeAmountsTest {

    @ParameterizedTest
    @CsvSource({
        "150000, VND, 150000",
        "150000.00, vnd, 150000",
        "1000, JPY, 1000",
        "12.34, USD, 1234",
        "12.3, usd, 1230",
        "0.01, EUR, 1"
    })
    @DisplayName("an amount becomes the currency's smallest unit: VND and JPY have no cents")
    void minorUnits(String amount, String currency, long expected) {
        assertThat(StripeAmounts.minorUnits(new BigDecimal(amount), currency)).isEqualTo(expected);
    }

    @Test
    @DisplayName("VND is never multiplied by 100: that would charge a hundred times too much")
    void vnd_isNotScaled() {
        assertThat(StripeAmounts.minorUnits(new BigDecimal("200000"), "VND")).isEqualTo(200_000L);
    }

    @ParameterizedTest
    @CsvSource({"100.5, VND", "100.5, JPY", "1.234, USD"})
    @DisplayName("an amount with more decimals than the currency has is refused, not rounded")
    void tooManyDecimals_isRefused(String amount, String currency) {
        assertThatThrownBy(() -> StripeAmounts.minorUnits(new BigDecimal(amount), currency))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a three-decimal currency is refused rather than guessed")
    void threeDecimalCurrency_isRefused() {
        assertThatThrownBy(() -> StripeAmounts.minorUnits(new BigDecimal("1.000"), "KWD"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("KWD");
    }

    @ParameterizedTest
    @CsvSource({"0, VND", "-5, USD"})
    @DisplayName("a zero or negative amount is refused")
    void nonPositive_isRefused(String amount, String currency) {
        assertThatThrownBy(() -> StripeAmounts.minorUnits(new BigDecimal(amount), currency))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
