package com.unihub.shared.domain;

/** How the web layer maps a {@link DomainException} onto an RFC 7807 status. */
public enum ProblemKind {
    /** The request itself is malformed or self-contradictory (HTTP 400), not a state clash. */
    INVALID_REQUEST,
    NOT_FOUND,
    CONFLICT,
    UNAVAILABLE
}
