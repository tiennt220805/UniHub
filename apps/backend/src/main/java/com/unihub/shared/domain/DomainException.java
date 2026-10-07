package com.unihub.shared.domain;

/** Base of every business-rule violation; declares a {@link ProblemKind}, never an HTTP status. */
public abstract class DomainException extends RuntimeException {

    protected DomainException(String message) {
        super(message);
    }

    protected DomainException(String message, Throwable cause) {
        super(message, cause);
    }

    public abstract ProblemKind kind();
}
