package com.unihub.payment.application.port.in;

public interface ReceivePaymentWebhookUseCase {

    /**
     * Verifies, then applies, one gateway webhook. Returns normally once the payload is durably
     * handled, which includes "unknown transaction" and "already resolved".
     *
     * @throws com.unihub.payment.domain.exception.InvalidWebhookSignatureException before any byte
     *     of the body is trusted
     */
    void receive(String rawBody, String signatureHeader);
}
