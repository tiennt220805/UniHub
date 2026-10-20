package com.unihub.registration.application.port.out;

/** A held seat lock. Closing releases it, and must happen on the thread that acquired it. */
public interface SeatLockHandle extends AutoCloseable {

    /** Idempotent: a second close is a no-op. */
    @Override
    void close();
}
