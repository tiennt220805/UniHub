package com.unihub.shared.messaging;

/**
 * Told by the shared error handler each time a message is moved to {@code unihub.parking-lot}, so a
 * module can raise the signal for its own queue (for example Payment's refund-stuck counter)
 * without the shared kernel knowing any module. Called after the message is safely parked; must not
 * throw, and a failure here is logged and ignored.
 */
public interface ParkedMessageObserver {

    /** The business queue the message was consumed from. */
    void parked(String queue);
}
