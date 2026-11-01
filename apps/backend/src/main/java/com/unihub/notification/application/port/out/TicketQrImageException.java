package com.unihub.notification.application.port.out;

/** A payload that cannot be drawn as a QR code. Deterministic: sending it again fails again. */
public class TicketQrImageException extends RuntimeException {

    public TicketQrImageException(String message, Throwable cause) {
        super(message, cause);
    }

    public TicketQrImageException(String message) {
        super(message);
    }
}
