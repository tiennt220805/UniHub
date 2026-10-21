package com.unihub.payment.application.port.out;

/**
 * The confirm provably never left UniHub: the connection was refused, the host did not resolve, or
 * the connect timed out (payment.md §3). No money can have moved, so the orchestrator may cancel
 * the payment and let a same-key retry run for real.
 *
 * <p>Raised only for causes on a short allow-list. Anything else, however unlikely to have been
 * sent, is {@link GatewayOutcomeUnknownException}: calling a sent confirm "not reached" would let a
 * retry charge the card a second time.
 */
public final class GatewayNotReachedException extends RuntimeException {
    public GatewayNotReachedException(String message, Throwable cause) {
        super(message, cause);
    }
}
