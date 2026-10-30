package com.unihub.registration.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code unihub.ticket.*} (checkin.md §4; Phase 5 checklist D49). {@code signingKey} is a private
 * EC P-256 JWK and comes from the environment only ({@code UNIHUB_TICKET_SIGNING_KEY}) — never from
 * committed config (INV-18).
 *
 * <p>The value is deliberately <em>not</em> validated here. A constructor that throws makes Boot
 * report a bind failure, and its failure analyzer prints the offending property value: for this
 * property that would put a private key in the log. {@code TicketSigningConfig} checks it in a bean
 * method instead, where the message is ours.
 */
@ConfigurationProperties(prefix = "unihub.ticket")
public record TicketSigningProperties(String signingKey) {

    /** A record prints its components; this one must not. */
    @Override
    public String toString() {
        return "TicketSigningProperties[signingKey=<redacted>]";
    }
}
