package com.unihub.workshop.api;

/**
 * A workshop's seat columns as a report shows them. {@code seatsTaken} counts {@code RESERVED} plus
 * {@code CONFIRMED} registrations (a hold takes a seat).
 */
public record SeatFigures(int capacity, int seatsTaken) {}
