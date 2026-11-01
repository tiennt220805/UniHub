package com.unihub.checkin.application.port.in;

import com.unihub.checkin.application.result.VerificationKeyResult;

public interface GetVerificationKeyUseCase {

    /**
     * The one key a device verifies tickets with, offline (checkin.md §6): the current key, never a
     * set. Permission is the caller's {@code CHECKIN_SCAN}, checked at the web edge.
     */
    VerificationKeyResult current();
}
