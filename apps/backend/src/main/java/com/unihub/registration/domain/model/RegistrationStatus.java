package com.unihub.registration.domain.model;

/** Mirrors the CHECK on {@code registrations.status} (database-schema.md §4.1). */
public enum RegistrationStatus {
    /** A seat held pending payment, until {@code hold_expires_at}. */
    RESERVED,
    /** A free ticket confirmed at once, or a paid one confirmed after payment. */
    CONFIRMED,
    CANCELLED,
    /** The hold lapsed before payment completed. */
    EXPIRED;

    /** Whether a registration in this status holds one of the workshop's seats. */
    public boolean occupiesSeat() {
        return this == RESERVED || this == CONFIRMED;
    }

    public boolean isTerminal() {
        return this == CANCELLED || this == EXPIRED;
    }
}
