package com.unihub.workshop.application.port.out;

import com.unihub.workshop.domain.model.Workshop;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The slow-changing part of a workshop, as cached for 300s (workshop.md §2). Only a flag for the
 * brochure, never its storage key. Everything that moves with a seat claim lives in {@link
 * WorkshopSeatsSnapshot}.
 */
public record WorkshopDetailSnapshot(
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
        boolean paid,
        BigDecimal price,
        boolean hasBrochure,
        String coverImageKey,
        Instant createdAt,
        int shape) {

    /**
     * The shape generation of this record. One key serves both releases of a rollout (INV-15), so
     * that a {@code DEL} on a write reaches the entry of either; an entry written by the other
     * release has another {@code shape} (a release that predates the field reads as {@code 0}) and
     * the cache treats it as a miss. Raise it whenever this record gains or loses a component. 1:
     * the first release with a cover image.
     */
    public static final int SHAPE = 1;

    public static WorkshopDetailSnapshot from(Workshop w) {
        return new WorkshopDetailSnapshot(
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
                w.isPaid(),
                w.price(),
                w.brochureS3Key() != null,
                w.coverImageKey(),
                w.createdAt(),
                SHAPE);
    }
}
