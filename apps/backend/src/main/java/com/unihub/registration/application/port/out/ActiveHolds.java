package com.unihub.registration.application.port.out;

/** How many seat holds are open right now (observability.md §4). */
public interface ActiveHolds {

    /**
     * Registrations still {@code RESERVED}: the 15-minute hold itself, not the few-second Redis
     * seat lock around one write ({@code unihub_registration_active_holds}).
     */
    long count();
}
