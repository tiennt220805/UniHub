package com.unihub.shared.application.port.out;

/** One message was not delivered; the row stays {@code PENDING} and is retried next cycle. */
public class BrokerPublishException extends RuntimeException {

    public BrokerPublishException(String message) {
        super(message);
    }

    public BrokerPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
