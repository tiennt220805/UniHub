package com.unihub.shared.application.port.out;

/** The broker cannot be reached; the relay ends the cycle instead of waiting once per row. */
public class BrokerUnavailableException extends RuntimeException {

    public BrokerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
