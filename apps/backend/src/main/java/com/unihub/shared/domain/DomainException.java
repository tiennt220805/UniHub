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

    /**
     * The problem {@code type} URI when a client must branch on this reason (§17, D8); {@code null}
     * keeps {@code about:blank}. A plain string, so the domain stays free of web types.
     */
    public String problemType() {
        return null;
    }
}
