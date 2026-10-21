package com.unihub.payment.infrastructure.out.client;

import com.unihub.payment.application.port.out.GatewayAdmission;

/**
 * Admits every call. Stands in for the circuit breaker until checklist 3.7 replaces it with the
 * Resilience4j adapter of the shared {@code paymentGateway} instance.
 */
public final class AlwaysAdmittingGatewayAdmission implements GatewayAdmission {

    @Override
    public boolean tryAcquire() {
        return true;
    }

    @Override
    public void release() {}
}
