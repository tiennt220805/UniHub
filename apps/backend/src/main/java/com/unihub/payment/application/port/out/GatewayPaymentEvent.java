package com.unihub.payment.application.port.out;

/**
 * What a verified webhook says about one payment: which gateway transaction, and what became of it.
 * Events about anything else never become one.
 */
public record GatewayPaymentEvent(String gatewayTransactionId, Outcome outcome) {

    public enum Outcome {
        SUCCEEDED,
        FAILED,
        CANCELLED
    }
}
