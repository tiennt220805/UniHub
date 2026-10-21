package com.unihub.payment.application.port.out;

import java.util.Optional;

/**
 * Proves a webhook came from the gateway, then reads it (payment.md §7). The verification is over
 * the raw bytes as received, before anything is parsed: re-serializing a parsed body would not
 * reproduce what was signed.
 */
public interface PaymentWebhookVerifier {

    /**
     * @param signatureHeader the gateway's signature header, possibly absent
     * @return the payment event, or empty when the (verified) event is about nothing this system
     *     acts on
     * @throws com.unihub.payment.domain.exception.InvalidWebhookSignatureException when the
     *     signature is absent, wrong, stale, or does not match the body; nothing was read
     */
    Optional<GatewayPaymentEvent> verify(String rawBody, String signatureHeader);
}
