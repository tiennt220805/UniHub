package com.unihub.payment.application.port.out;

/**
 * The gateway was not called because its circuit is open (payment.md §2). Only the calls that are
 * allowed to be skipped see this one: a reconciliation that meets it leaves the payment {@code
 * PENDING} and lets the next sweep try again (payment.md §5). The charge's own calls get {@link
 * GatewayCallFailedException} or {@link GatewayNotReachedException} instead, because the
 * orchestrator must know whether anything could have been sent.
 *
 * <p>For the refund consumer this is a gateway that was never asked: the refund is owed and nothing
 * was attempted, so the message waits in its retry chain instead of being parked (checklist D25,
 * messaging.md §4). Any other queue that meets it follows the three-attempt rule.
 */
public final class GatewayCircuitOpenException extends RuntimeException
        implements com.unihub.shared.domain.RetryUntilAvailable {
    public GatewayCircuitOpenException(String message, Throwable cause) {
        super(message, cause);
    }
}
