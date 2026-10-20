package com.unihub.shared.domain.id;

import java.util.Objects;
import java.util.UUID;

public record RegistrationId(UUID value) {

    public RegistrationId {
        Objects.requireNonNull(value);
    }

    public static RegistrationId newId() {
        return new RegistrationId(UUID.randomUUID());
    }

    public static RegistrationId of(UUID value) {
        return new RegistrationId(value);
    }
}
