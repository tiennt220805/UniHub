package com.unihub.payment.application.usecase;

import com.unihub.payment.application.command.RequireRefundCommand;
import com.unihub.payment.application.port.in.RequireRefundUseCase;
import com.unihub.shared.domain.OptimisticConflictException;
import org.springframework.stereotype.Service;

/**
 * Entry point of the refund hand-off — NOT {@code @Transactional}, so that a lost version race is
 * retried in a new transaction, whose re-read finds the payment already on its way. Only when every
 * attempt loses does the exception reach the shared error handler.
 */
@Service
class RequireRefund implements RequireRefundUseCase {
    private static final int ATTEMPTS = 3;

    private final RequireRefundTransaction transaction;

    RequireRefund(RequireRefundTransaction transaction) {
        this.transaction = transaction;
    }

    @Override
    public void requireRefund(RequireRefundCommand command) {
        for (int attempt = 1; ; attempt++) {
            try {
                transaction.attempt(command);
                return;
            } catch (OptimisticConflictException lostRace) {
                if (attempt >= ATTEMPTS) {
                    throw lostRace;
                }
            }
        }
    }
}
