package com.unihub.payment.application.port.in;

import com.unihub.payment.application.command.ReconcilePaymentCommand;

public interface ReconcilePaymentUseCase {

    /**
     * Resolves a payment that stayed {@code PENDING} from the gateway's own record (payment.md §6).
     * A payment already resolved, by the webhook or an earlier message, is left as it is. A payment
     * the gateway is still working on stays {@code PENDING} for the next sweep.
     *
     * @throws com.unihub.payment.application.port.out.GatewayCallFailedException when the gateway
     *     cannot be asked; the message goes to the broker's retry chain
     */
    void reconcile(ReconcilePaymentCommand command);
}
