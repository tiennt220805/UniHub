package com.unihub.workshop.domain.model;

/** Mirrors the {@code workshops.status} CHECK values. */
public enum WorkshopStatus {
    DRAFT,
    PUBLISHED,
    CANCELLED,
    COMPLETED;

    public boolean isTerminal() {
        return this == CANCELLED || this == COMPLETED;
    }
}
