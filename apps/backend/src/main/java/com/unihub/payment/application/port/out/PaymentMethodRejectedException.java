package com.unihub.payment.application.port.out;

/**
 * The gateway refused the payment method token itself (an unknown or unusable {@code pm_...}).
 * Nothing was charged and a retry with the same token can never succeed, so the orchestrator
 * records it as a declined payment ({@code FAILED}) instead of the retryable {@code 503} of a
 * {@link GatewayCallFailedException}. It reflects bad input, not the gateway's health: the circuit
 * breaker (checklist 3.7) must not count it as a failure, or one client sending junk tokens could
 * open it for everyone.
 */
public final class PaymentMethodRejectedException extends RuntimeException {
    public PaymentMethodRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
