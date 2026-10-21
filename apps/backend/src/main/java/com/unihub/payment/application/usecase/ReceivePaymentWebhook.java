package com.unihub.payment.application.usecase;

import com.unihub.payment.application.port.in.ReceivePaymentWebhookUseCase;
import com.unihub.payment.application.port.out.PaymentWebhookVerifier;
import com.unihub.shared.domain.OptimisticConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Entry point of the gateway webhook — NOT {@code @Transactional}: a transaction that loses its
 * version race is retried in a new one, which only works from outside (the same shape as the seat
 * claim). The signature is verified before anything else, and a webhook that is acknowledged is
 * durably handled; a failure leaves it unacknowledged so the gateway redelivers (payment.md §7).
 */
@Service
class ReceivePaymentWebhook implements ReceivePaymentWebhookUseCase {
    private static final Logger LOG = LoggerFactory.getLogger(ReceivePaymentWebhook.class);
    private static final int ATTEMPTS = 3;

    private final PaymentWebhookVerifier verifier;
    private final PaymentWebhookTransaction transaction;

    ReceivePaymentWebhook(PaymentWebhookVerifier verifier, PaymentWebhookTransaction transaction) {
        this.verifier = verifier;
        this.transaction = transaction;
    }

    @Override
    public void receive(String rawBody, String signatureHeader) {
        var event = verifier.verify(rawBody, signatureHeader);
        if (event.isEmpty()) {
            LOG.debug("Payment webhook is about nothing this system acts on; acknowledged");
            return;
        }
        for (int attempt = 1; ; attempt++) {
            try {
                var effect = transaction.apply(event.get());
                if (effect == PaymentWebhookTransaction.Effect.UNKNOWN_TRANSACTION) {
                    // Not an error: a test ping, or a delivery for a transaction we never made.
                    LOG.info("Payment webhook for an unknown gateway transaction; discarded");
                }
                return;
            } catch (OptimisticConflictException lostRace) {
                if (attempt >= ATTEMPTS) {
                    throw lostRace;
                }
            }
        }
    }
}
