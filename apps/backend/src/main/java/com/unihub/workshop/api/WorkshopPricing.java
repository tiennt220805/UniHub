package com.unihub.workshop.api;

import com.unihub.shared.domain.id.WorkshopId;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * The price Payment charges for a workshop's ticket. Read inside the caller's transaction ({@code
 * MANDATORY}) so the amount charged is the one on the Master, not a Replica's older copy.
 */
public interface WorkshopPricing {

    /** The ticket price; empty when the workshop is free or does not exist. */
    Optional<BigDecimal> priceOf(WorkshopId workshopId);
}
