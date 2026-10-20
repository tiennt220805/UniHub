package com.unihub.registration.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code unihub.registration.*} (registration.md §2). */
@ConfigurationProperties(prefix = "unihub.registration")
public record RegistrationProperties(
        Duration seatHold, Duration lockWait, Duration lockLease, int seatClaimAttempts) {

    public RegistrationProperties {
        requirePositive("seat-hold", seatHold);
        requirePositive("lock-wait", lockWait);
        requirePositive("lock-lease", lockLease);
        if (seatClaimAttempts < 1) {
            throw new IllegalArgumentException(
                    "unihub.registration.seat-claim-attempts must be at least 1");
        }
    }

    private static void requirePositive(String name, Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("unihub.registration." + name + " is required");
        }
    }
}
