package com.unihub.workshop.api;

/** What {@link SeatInventory#claim} decided. */
public sealed interface SeatClaimOutcome {

    /** One seat taken; {@code paid} is the workshop's own flag, never the client's. */
    record Claimed(boolean paid) implements SeatClaimOutcome {}

    record SoldOut() implements SeatClaimOutcome {}

    /** Not {@code PUBLISHED}: still a draft, cancelled or completed. */
    record NotOpenForRegistration() implements SeatClaimOutcome {}
}
