package com.unihub.payment.infrastructure.out.client;

import com.unihub.payment.application.port.out.PaymentGateway;

/** Lets the configuration build the package-private adapter without widening its visibility. */
public final class StripeGatewayFactory {
    private StripeGatewayFactory() {}

    public static PaymentGateway create(StripeProperties properties) {
        return new StripePaymentGateway(properties);
    }
}
