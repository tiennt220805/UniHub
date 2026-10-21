package com.unihub.payment.infrastructure.out.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StripePropertiesTest {

    @Test
    @DisplayName("defaults: the real API base and the 3s ceiling for both timeouts")
    void defaults() {
        var properties = new StripeProperties("key", null, null, null);

        assertThat(properties.apiBase()).isEqualTo("https://api.stripe.com");
        assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("a timeout above 3s, or not positive, is refused at startup (payment.md §3)")
    void timeouts_haveABudget() {
        assertThatThrownBy(() -> new StripeProperties("k", null, Duration.ofSeconds(4), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StripeProperties("k", null, null, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the secret key never appears in the printed properties")
    void toString_hidesTheKey() {
        var properties = new StripeProperties("a-very-secret-value", null, null, null);

        assertThat(properties.toString()).doesNotContain("a-very-secret-value");
    }
}
