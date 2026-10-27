package com.unihub.registration.application.port.out;

/** The signal of the seat-hold expiry consumer that observability.md §4 names. */
public interface SeatHoldExpiryMetrics {

    /**
     * The seat lock could not be taken, so the expiry waits in its retry chain ({@code
     * unihub_seat_hold_expiry_lock_unavailable_total}).
     */
    void lockUnavailable();
}
