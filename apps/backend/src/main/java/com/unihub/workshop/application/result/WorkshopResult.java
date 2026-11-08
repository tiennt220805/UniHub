package com.unihub.workshop.application.result;

import com.unihub.workshop.application.port.out.WorkshopDetailSnapshot;
import com.unihub.workshop.application.port.out.WorkshopSeatsSnapshot;
import com.unihub.workshop.domain.model.Workshop;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The workshop as the three write use cases (create, update, cancel) return it. One shared Result:
 * all three answer with the full resulting workshop (packages/api-contract {@code Workshop}), so
 * three identical records would add nothing.
 */
public record WorkshopResult(
        UUID id,
        String title,
        String description,
        String aiSummary,
        String speakerName,
        String speakerBio,
        String location,
        Instant startTime,
        Instant endTime,
        int capacity,
        int seatsTaken,
        boolean paid,
        BigDecimal price,
        boolean hasBrochure,
        String coverImageUrl,
        String status,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public static WorkshopResult from(Workshop w) {
        return new WorkshopResult(
                w.id().value(),
                w.title(),
                w.description(),
                w.aiSummary(),
                w.speakerName(),
                w.speakerBio(),
                w.location(),
                w.startTime(),
                w.endTime(),
                w.capacity(),
                w.seatsTaken(),
                w.isPaid(),
                w.price(),
                w.brochureS3Key() != null,
                CoverImages.urlOf(w.coverImageKey()),
                w.status().name(),
                w.version().value(),
                w.createdAt(),
                w.updatedAt());
    }

    /**
     * The two cached halves put back together. Where both carry a field (capacity), the 3s seats
     * snapshot wins: it is the one invalidated by every seat or status change.
     */
    public static WorkshopResult from(WorkshopDetailSnapshot detail, WorkshopSeatsSnapshot seats) {
        return new WorkshopResult(
                detail.id(),
                detail.title(),
                detail.description(),
                detail.aiSummary(),
                detail.speakerName(),
                detail.speakerBio(),
                detail.location(),
                detail.startTime(),
                detail.endTime(),
                seats.capacity(),
                seats.seatsTaken(),
                detail.paid(),
                detail.price(),
                detail.hasBrochure(),
                CoverImages.urlOf(detail.coverImageKey()),
                seats.status(),
                seats.version(),
                detail.createdAt(),
                seats.updatedAt());
    }
}
