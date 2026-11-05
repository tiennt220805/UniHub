package com.unihub.shared.infrastructure.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code unihub.rate-limit.*} (distributed-coordination.md §3, §5; Phase 7 checklist D90). On by
 * default and on the cluster; off in the shared integration-test suite, where every test calls from
 * one address, and on only in the limiter's own tests. {@code failureCooldown} is how long a pod
 * leaves Redis alone after it failed to answer (§4).
 */
@ConfigurationProperties(prefix = "unihub.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("100") int generalLimit,
        @DefaultValue("PT60S") Duration generalWindow,
        @DefaultValue("5") int registrationLimit,
        @DefaultValue("PT10S") Duration registrationWindow,
        @DefaultValue("PT5S") Duration failureCooldown) {

    public RateLimitProperties {
        if (generalLimit < 1 || registrationLimit < 1) {
            throw new IllegalArgumentException("unihub.rate-limit limits must be at least 1");
        }
        for (var window : new Duration[] {generalWindow, registrationWindow, failureCooldown}) {
            if (window.isNegative() || window.isZero()) {
                throw new IllegalArgumentException("unihub.rate-limit durations must be positive");
            }
        }
    }
}
