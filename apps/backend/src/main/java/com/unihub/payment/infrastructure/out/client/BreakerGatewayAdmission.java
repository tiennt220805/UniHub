package com.unihub.payment.infrastructure.out.client;

import com.unihub.payment.application.port.out.GatewayAdmission;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/**
 * Asks the shared circuit whether it would let a call through, without keeping the permission. The
 * ask itself is what moves a circuit whose open state has run out to half-open, which is why this
 * does not just read the state. The permission is returned at once; the call that follows asks for
 * its own.
 */
public final class BreakerGatewayAdmission implements GatewayAdmission {
    private final CircuitBreaker breaker;

    public BreakerGatewayAdmission(CircuitBreaker breaker) {
        this.breaker = breaker;
    }

    @Override
    public boolean admitting() {
        if (!breaker.tryAcquirePermission()) {
            return false;
        }
        breaker.releasePermission();
        return true;
    }
}
