package com.unihub.registration.api;

/**
 * Registration's answer to "which key verifies a ticket?" for Check-in, which serves it at {@code
 * GET /api/v1/checkin/verification-key} (checkin.md §6; Phase 5 checklist D47; backend-coding-
 * standards.md §7, interaction type 1). Only the public key crosses the module boundary: the
 * private key stays with the one module that signs.
 */
public interface TicketVerificationKey {

    /** The current key: one, never a set (a rotation replaces it, checkin.md §6). */
    PublicTicketKey current();
}
