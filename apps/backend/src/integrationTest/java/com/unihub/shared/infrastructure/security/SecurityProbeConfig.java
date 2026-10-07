package com.unihub.shared.infrastructure.security;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoints that stand in for "a business write": each counts how often its handler runs,
 * so a test can prove a rejected request never reached it. {@code /probe} needs only a valid token;
 * {@code /guarded} also needs the {@code WORKSHOP_CREATE} permission, the way a real controller
 * will declare it (backend-coding-standards.md §11). The real guarded {@code POST /workshops}
 * arrives with M-04.
 */
@TestConfiguration
class SecurityProbeConfig {

    static final String PROBE_PATH = "/api/v1/test-only/probe";
    static final String GUARDED_PATH = "/api/v1/test-only/guarded";

    @Bean
    AtomicInteger probeInvocations() {
        return new AtomicInteger();
    }

    // Probe is a member class of this configuration, so Spring registers it by itself (constructor
    // injection of the counter above). A second @Bean for it would map every path twice.
    @RestController
    static class Probe {
        private final AtomicInteger invocations;

        Probe(AtomicInteger invocations) {
            this.invocations = invocations;
        }

        @GetMapping(PROBE_PATH)
        String probe() {
            invocations.incrementAndGet();
            return "reached";
        }

        @GetMapping(GUARDED_PATH)
        @PreAuthorize("hasAuthority('WORKSHOP_CREATE')")
        String guarded() {
            invocations.incrementAndGet();
            return "reached";
        }
    }
}
