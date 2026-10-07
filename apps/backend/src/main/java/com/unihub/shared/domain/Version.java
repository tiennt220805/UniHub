package com.unihub.shared.domain;

/** Optimistic-locking version of a Tier A aggregate; mirrors the {@code version} column. */
public record Version(long value) {

    public static Version initial() {
        return new Version(0);
    }
}
