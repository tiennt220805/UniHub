package com.unihub.registration.application.usecase;

import com.unihub.registration.application.command.ConfirmRegistrationCommand;
import com.unihub.registration.application.port.in.ConfirmRegistrationUseCase;
import com.unihub.shared.domain.OptimisticConflictException;
import org.springframework.stereotype.Service;

/**
 * Entry point of the confirmation hand-off — NOT {@code @Transactional}, so that a conditional
 * update lost to the hold-expiry (or to a duplicate delivery) is retried in a new transaction. The
 * re-read then finds the registration {@code EXPIRED} or {@code CONFIRMED} and takes that branch: a
 * lost race is never an error (checklist D2 constraint 1). Only when every attempt loses does the
 * exception reach the shared error handler, which retries the message.
 */
@Service
class ConfirmRegistration implements ConfirmRegistrationUseCase {
    private static final int ATTEMPTS = 3;

    private final ConfirmRegistrationTransaction transaction;

    ConfirmRegistration(ConfirmRegistrationTransaction transaction) {
        this.transaction = transaction;
    }

    @Override
    public void confirm(ConfirmRegistrationCommand command) {
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
