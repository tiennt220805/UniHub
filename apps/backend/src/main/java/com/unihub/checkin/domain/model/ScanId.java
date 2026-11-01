package com.unihub.checkin.domain.model;

import java.util.Objects;
import java.util.UUID;

/** A scan's identity, generated on the device (database-schema.md §6.1). */
public record ScanId(UUID value) {

    public ScanId {
        Objects.requireNonNull(value);
    }
}
