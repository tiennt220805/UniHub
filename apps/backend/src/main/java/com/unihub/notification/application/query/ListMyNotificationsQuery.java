package com.unihub.notification.application.query;

import com.unihub.shared.domain.id.UserId;
import java.util.Objects;

/**
 * The caller's own in-app inbox. There is no way to name another user: the list is always scoped to
 * the caller, who is the JWT's subject.
 *
 * @param status only the notifications in the delivery status of this name, or null for all
 */
public record ListMyNotificationsQuery(UserId caller, String status, int page, int size) {

    public ListMyNotificationsQuery {
        Objects.requireNonNull(caller);
        if (page < 0 || size < 1) {
            throw new IllegalArgumentException("page must be >= 0 and size >= 1");
        }
    }
}
