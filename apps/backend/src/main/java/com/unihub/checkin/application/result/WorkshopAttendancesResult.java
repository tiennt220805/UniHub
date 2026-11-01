package com.unihub.checkin.application.result;

import com.unihub.checkin.application.port.out.AttendancePage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A page of a workshop's attendances. */
public record WorkshopAttendancesResult(
        List<Item> items, int page, int size, long totalElements, int totalPages) {

    public record Item(UUID registrationId, Instant checkedInAt) {}

    public static WorkshopAttendancesResult of(AttendancePage page) {
        return new WorkshopAttendancesResult(
                page.items().stream()
                        .map(e -> new Item(e.registrationId().value(), e.checkedInAt()))
                        .toList(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages());
    }
}
