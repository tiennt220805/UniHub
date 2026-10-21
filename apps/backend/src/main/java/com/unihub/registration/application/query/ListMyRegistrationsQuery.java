package com.unihub.registration.application.query;

import com.unihub.shared.domain.id.UserId;
import java.util.Objects;

/**
 * The caller's own registrations. There is no way to name another user: the list is always scoped
 * to the caller.
 *
 * @param status only the registrations in the status of this name, or null for all
 */
public record ListMyRegistrationsQuery(UserId caller, String status, int page, int size) {

    public ListMyRegistrationsQuery {
        Objects.requireNonNull(caller);
        if (page < 0 || size < 1) {
            throw new IllegalArgumentException("page must be >= 0 and size >= 1");
        }
    }
}
