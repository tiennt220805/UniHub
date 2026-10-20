package com.unihub.registration.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RegistrationPropertiesTest {
    private static final Duration SECONDS = Duration.ofSeconds(2);

    @Test
    @DisplayName("the values of registration.md §2 are accepted")
    void valid() {
        var properties = new RegistrationProperties(Duration.ofMinutes(15), SECONDS, SECONDS, 3);

        assertThat(properties.seatClaimAttempts()).isEqualTo(3);
    }

    @Test
    @DisplayName(
            "a missing or non-positive duration, or fewer than one attempt, is refused at boot")
    void invalid() {
        assertThatThrownBy(() -> new RegistrationProperties(null, SECONDS, SECONDS, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RegistrationProperties(SECONDS, Duration.ZERO, SECONDS, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RegistrationProperties(SECONDS, SECONDS, SECONDS.negated(), 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RegistrationProperties(SECONDS, SECONDS, SECONDS, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
