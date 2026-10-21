package com.unihub.registration.application.result;

import com.unihub.registration.application.port.out.RegistrationPage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A page of the caller's registrations, in the list projection (no ticket payload). */
public record MyRegistrationsResult(
        List<Item> items, int page, int size, long totalElements, int totalPages) {

    public record Item(
            UUID id,
            UUID workshopId,
            UUID userId,
            String ticketType,
            String status,
            Instant createdAt) {}

    public static MyRegistrationsResult of(RegistrationPage page) {
        return new MyRegistrationsResult(
                page.items().stream()
                        .map(
                                r ->
                                        new Item(
                                                r.id().value(),
                                                r.workshopId().value(),
                                                r.userId().value(),
                                                r.ticketType().name(),
                                                r.status().name(),
                                                r.createdAt()))
                        .toList(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages());
    }
}
