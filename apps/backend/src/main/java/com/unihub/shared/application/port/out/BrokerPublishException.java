package com.unihub.shared.application.port.out;

/**
 * The broker answered and did not take this message (nack, unroutable); the row stays {@code
 * PENDING} and is retried next cycle.
 */
public class BrokerPublishException extends RuntimeException {

    public BrokerPublishException(String message) {
        super(message);
    }

    public BrokerPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
