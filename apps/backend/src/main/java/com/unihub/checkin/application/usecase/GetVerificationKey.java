package com.unihub.checkin.application.usecase;

import com.unihub.checkin.application.port.in.GetVerificationKeyUseCase;
import com.unihub.checkin.application.port.out.VerificationKeys;
import com.unihub.checkin.application.result.VerificationKeyResult;
import org.springframework.stereotype.Service;

/** Hands the current verification key to the Check-in Client (checkin.md §6; M-48). */
@Service
class GetVerificationKey implements GetVerificationKeyUseCase {
    private final VerificationKeys keys;

    GetVerificationKey(VerificationKeys keys) {
        this.keys = keys;
    }

    @Override
    public VerificationKeyResult current() {
        return keys.current();
    }
}
