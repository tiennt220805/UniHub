package com.unihub.workshop.application.command;

import java.util.Set;
import java.util.UUID;

/** {@code permissions} is the caller's resolved permission set; an anonymous caller has none. */
public record GetWorkshopQuery(UUID workshopId, Set<String> permissions) {

    public GetWorkshopQuery {
        permissions = Set.copyOf(permissions);
    }
}
