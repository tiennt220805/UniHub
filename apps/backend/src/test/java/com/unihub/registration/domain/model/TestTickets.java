package com.unihub.registration.domain.model;

/** A ticket for tests that only need one to exist; the signature is not real. */
public final class TestTickets {

    private TestTickets() {}

    public static Ticket aTicket() {
        return new Ticket("0123456789ABCDEFGHJKMNPQRS", "header.payload.signature");
    }

    /** One that differs from every other: {@code ticket_code} is UNIQUE, and a test may commit. */
    public static Ticket aUniqueTicket() {
        var code = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 26);
        return new Ticket(code.toUpperCase(java.util.Locale.ROOT), "h." + code + ".s");
    }
}
