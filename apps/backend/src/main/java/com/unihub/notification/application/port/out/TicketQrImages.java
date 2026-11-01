package com.unihub.notification.application.port.out;

/**
 * Draws a ticket's QR payload as an image for the confirmation email (Phase 5 checklist D55).
 * Deterministic: the same payload always gives the same bytes.
 */
public interface TicketQrImages {

    /**
     * A PNG of the payload at error-correction level Q.
     *
     * @throws TicketQrImageException when the payload cannot be drawn, for a reason that retrying
     *     cannot change (empty, or too long for a QR code)
     */
    byte[] pngOf(String payload);
}
