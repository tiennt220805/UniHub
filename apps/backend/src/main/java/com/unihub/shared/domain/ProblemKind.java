package com.unihub.shared.domain;

/** How the web layer maps a {@link DomainException} onto an RFC 7807 status. */
public enum ProblemKind {
    NOT_FOUND,
    CONFLICT,
    UNAVAILABLE
}
