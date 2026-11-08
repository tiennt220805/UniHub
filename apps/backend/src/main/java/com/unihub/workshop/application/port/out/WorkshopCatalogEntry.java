package com.unihub.workshop.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of the public catalog (workshop.md §2): what a card needs to draw itself (Phase 8, D117).
 * Cached for 30s as part of a page; {@code seatsTaken} is therefore up to that stale, which is why
 * the catalog shows a level, never a count (D118).
 */
public record WorkshopCatalogEntry(
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
        String coverImageKey) {}
