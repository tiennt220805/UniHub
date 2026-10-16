package com.unihub.shared.application.port.out;

import java.util.Objects;

/**
 * A recorded use-case Result as the store keeps it: {@code type} is the simple name of the sealed
 * Result's variant (e.g. {@code Confirmed}, {@code SoldOut}) and {@code body} its JSON. The store
 * cannot rebuild the Java type itself — {@code shared} never imports a module's Result — so the
 * caller, which knows its own sealed interface, maps {@code type} back to a variant.
 */
public record StoredResult(String type, String body) {

    public StoredResult {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(body, "body");
    }
}
