package com.unihub.payment.application.port.out;

/**
 * What the gateway answered to a confirm. An answer is a known outcome; no answer is an exception.
 */
public sealed interface ConfirmOutcome {

    /** The charge went through. */
    record Succeeded() implements ConfirmOutcome {}

    /**
     * The charge was refused (a declined card, or a payment that would need the customer's action).
     */
    record Declined(String reason) implements ConfirmOutcome {}

    /** The gateway accepted the charge and is still working on it: the outcome comes later. */
    record Processing() implements ConfirmOutcome {}
}
