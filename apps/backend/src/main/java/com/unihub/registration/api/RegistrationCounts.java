package com.unihub.registration.api;

/**
 * How many registrations of a workshop are in each status that a report shows: {@code CONFIRMED}
 * and {@code RESERVED} (an unpaid hold). {@code EXPIRED} and {@code CANCELLED} are in neither.
 */
public record RegistrationCounts(long confirmed, long reserved) {}
