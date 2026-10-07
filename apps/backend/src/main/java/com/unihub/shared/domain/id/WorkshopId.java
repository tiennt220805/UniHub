package com.unihub.shared.domain.id;

import java.util.Objects;
import java.util.UUID;

public record WorkshopId(UUID value) {

    public WorkshopId {
        Objects.requireNonNull(value);
    }

    public static WorkshopId newId() {
        return new WorkshopId(UUID.randomUUID());
    }

    public static WorkshopId of(UUID value) {
        return new WorkshopId(value);
    }
}
