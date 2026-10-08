package com.unihub.workshop.domain.event;

import com.unihub.shared.domain.id.WorkshopId;
import java.time.Instant;

public record WorkshopRescheduled(
        WorkshopId workshopId,
        Instant previousStartTime,
        Instant previousEndTime,
        Instant newStartTime,
        Instant newEndTime,
        Instant occurredAt)
        implements WorkshopDomainEvent {}
