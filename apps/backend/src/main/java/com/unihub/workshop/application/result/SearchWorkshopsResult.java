package com.unihub.workshop.application.result;

import com.unihub.workshop.application.port.out.WorkshopCatalogPage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SearchWorkshopsResult(
        List<Item> items, int page, int size, long totalElements, int totalPages) {

    public record Item(UUID id, String title, String location, Instant startTime, String status) {}

    public static SearchWorkshopsResult of(WorkshopCatalogPage page) {
        List<Item> items =
                page.items().stream()
                        .map(
                                e ->
                                        new Item(
                                                e.id(),
                                                e.title(),
                                                e.location(),
                                                e.startTime(),
                                                e.status()))
                        .toList();
        return new SearchWorkshopsResult(
                items, page.page(), page.size(), page.totalElements(), page.totalPages());
    }
}
