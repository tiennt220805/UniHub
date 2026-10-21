package com.unihub.payment.infrastructure.out.client;

import com.unihub.payment.application.port.out.ConfirmOutcome;
import com.unihub.payment.application.port.out.GatewayCallFailedException;
import com.unihub.payment.application.port.out.PaymentGateway;
import com.unihub.shared.domain.id.PaymentId;
import java.math.BigDecimal;

/**
 * The gateway when no Stripe secret key is configured: the application still boots, and a charge
 * answers {@code 503} because the very first call fails before anything is confirmed. Nothing is
 * charged and nothing is recorded, so the same request works once a key is set.
 */
public final class UnconfiguredPaymentGateway implements PaymentGateway {

    @Override
    public String create(
            PaymentId paymentId, BigDecimal amount, String currency, String paymentMethodToken) {
        throw new GatewayCallFailedException(
                "no payment gateway is configured (unihub.payment.stripe.secret-key)", null);
    }

    @Override
    public ConfirmOutcome confirm(PaymentId paymentId, String gatewayTransactionId) {
        throw new IllegalStateException("confirm cannot be reached without a created payment");
    }
}
