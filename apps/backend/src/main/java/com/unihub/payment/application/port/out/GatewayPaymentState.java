package com.unihub.payment.application.port.out;

/**
 * What the gateway says became of one payment, in this system's words (payment.md §6, checklist
 * D16). The adapter translates the gateway's own statuses, so nothing above it knows them.
 */
public enum GatewayPaymentState {

    /** The charge went through. */
    SUCCEEDED,

    /** A confirm was attempted and the card was refused. */
    DECLINED,

    /** The payment was never confirmed, and can still be cancelled. */
    NEVER_CONFIRMED,

    /** In a state that cannot occur on this system's path, and can be cancelled: cancel it. */
    CANCEL_REQUIRED,

    /** The gateway accepted the charge and is still working: ask again later, never cancel. */
    PROCESSING,

    /** The gateway's payment was cancelled. */
    CANCELED
}
