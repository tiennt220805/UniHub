package com.unihub.registration;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.util.Base64URL;
import com.unihub.registration.api.PublicTicketKey;
import java.text.ParseException;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What the integration tests of Phase 5 ask of a ticket (checkin.md §3, §4): that the compact JWS
 * stored in {@code registrations.qr_payload} verifies with the <em>published</em> public key (the
 * one {@code TicketVerificationKey} hands to Check-in), not with a key the test knows from its own
 * configuration, and that it says what it should.
 */
public final class TicketITSupport {

    private TicketITSupport() {}

    /** {@code ticket_code} and {@code qr_payload} of a row; both {@code null} before a ticket. */
    public record StoredTicket(String code, String qrPayload) {}

    public static StoredTicket storedTicket(JdbcTemplate jdbc, UUID registration) {
        return jdbc.queryForObject(
                "SELECT ticket_code, qr_payload FROM registrations WHERE id = ?",
                (rs, n) -> new StoredTicket(rs.getString(1), rs.getString(2)),
                registration);
    }

    /**
     * Asserts the payload is an ES256 JWS that the published key verifies, and that it carries the
     * given code and workshop; returns its claims.
     */
    public static Map<String, Object> assertVerifies(
            StoredTicket ticket, PublicTicketKey published, UUID workshop)
            throws ParseException, JOSEException {
        assertThat(ticket.code()).as("ticket_code").isNotNull();
        assertThat(ticket.qrPayload()).as("qr_payload").isNotNull();
        var jws = JWSObject.parse(ticket.qrPayload());
        assertThat(jws.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.ES256);
        var key =
                new ECKey.Builder(
                                Curve.P_256,
                                new Base64URL(published.x()),
                                new Base64URL(published.y()))
                        .build();
        assertThat(jws.verify(new ECDSAVerifier(key.toECPublicKey())))
                .as("the published key verifies the stored ticket")
                .isTrue();
        var claims = jws.getPayload().toJSONObject();
        assertThat(claims.get("ticket_code")).isEqualTo(ticket.code());
        assertThat(claims.get("workshop_id")).isEqualTo(workshop.toString());
        return claims;
    }
}
