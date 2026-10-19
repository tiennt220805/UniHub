package com.unihub.shared.messaging;

/**
 * A consumer throws this for a failure that retrying cannot fix — an unparseable payload, an
 * unsupported {@code schemaVersion} (backend-coding-standards.md §8.6). The shared error handler
 * sends the message straight to {@code unihub.parking-lot} instead of walking the retry chain.
 */
public class NonRetryableMessageException extends RuntimeException {

    public NonRetryableMessageException(String message) {
        super(message);
    }

    public NonRetryableMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
