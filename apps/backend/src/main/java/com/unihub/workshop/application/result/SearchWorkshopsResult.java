package com.unihub.workshop.application.result;

import com.unihub.workshop.application.port.out.WorkshopCatalogPage;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SearchWorkshopsResult(
        List<Item> items, int page, int size, long totalElements, int totalPages) {

    public record Item(
            UUID id,
            String title,
            String location,
            Instant startTime,
            String status,
            String speakerName,
            Instant endTime,
            int capacity,
            int seatsTaken,
            boolean paid,
            BigDecimal price,
            String coverImageUrl) {}

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
                                                e.status(),
                                                e.speakerName(),
                                                e.endTime(),
                                                e.capacity(),
                                                e.seatsTaken(),
                                                e.paid(),
                                                e.price(),
                                                CoverImages.urlOf(e.coverImageKey())))
                        .toList();
        return new SearchWorkshopsResult(
                items, page.page(), page.size(), page.totalElements(), page.totalPages());
    }
}
