package com.unihub.payment.application.port.in;

public interface RequestPaymentReconciliationsUseCase {

    /**
     * The sweep's query half (payment.md §6): one reconciliation request, through the outbox, for
     * every payment {@code PENDING} for longer than the threshold. Reads nothing from the gateway.
     *
     * @return how many requests were written
     */
    int requestReconciliations();
}
