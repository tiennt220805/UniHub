package com.unihub.checkin.application.port.out;

/** Registrations of one workshop in each status a report shows. */
public record RegistrationTotals(long confirmed, long reserved) {}
