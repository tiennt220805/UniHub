package com.unihub.checkin.application.port.out;

/** A workshop's capacity and the seats taken (a hold takes a seat), as the report shows them. */
public record ReportedSeats(int capacity, int seatsTaken) {}
