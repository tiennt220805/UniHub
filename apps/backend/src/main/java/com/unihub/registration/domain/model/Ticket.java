package com.unihub.registration.domain.model;

/**
 * The e-ticket a registration is confirmed with (checkin.md §3, §4): the {@code ticket_code} that
 * identifies it and the signed {@code qr_payload} the Web Application renders as a QR code. Both or
 * neither: a registration is never confirmed with a code and no payload, or the reverse. Rows
 * confirmed before Phase 5 have no ticket and are restored without one; a new confirmation always
 * carries one.
 */
public record Ticket(String code, String qrPayload) {

    public Ticket {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("a ticket needs a code");
        }
        if (qrPayload == null || qrPayload.isBlank()) {
            throw new IllegalArgumentException("a ticket needs a signed payload");
        }
    }

    /** The payload is a signed credential: it stays out of logs and exception messages. */
    @Override
    public String toString() {
        return "Ticket[code=" + code + ", qrPayload=<redacted>]";
    }
}
