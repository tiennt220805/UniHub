package com.unihub.workshop.domain.event;

import com.unihub.shared.domain.id.WorkshopId;
import java.time.Instant;

public record WorkshopCancelled(WorkshopId workshopId, Instant occurredAt)
        implements WorkshopDomainEvent {}
