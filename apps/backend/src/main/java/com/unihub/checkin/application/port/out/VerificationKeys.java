package com.unihub.checkin.application.port.out;

import com.unihub.checkin.application.result.VerificationKeyResult;

/**
 * Where Check-in learns which key verifies a ticket (Phase 5 checklist D47). Check-in does not hold
 * the key: the adapter asks Registration's {@code api}, which hands out public material only.
 */
public interface VerificationKeys {

    VerificationKeyResult current();
}
