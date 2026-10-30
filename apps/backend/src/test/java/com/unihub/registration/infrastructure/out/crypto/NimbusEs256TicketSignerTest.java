package com.unihub.registration.infrastructure.out.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.util.Base64URL;
import com.unihub.shared.domain.id.WorkshopId;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the ticket looks like on the wire (Phase 5 checklist D48, AC48.3, AC47.2): the exact header
 * and claims, a signature that verifies with the published public key and nothing else, and a
 * public key with exactly the four members the contract promises.
 */
class NimbusEs256TicketSignerTest {

    private static final Instant ISSUED = Instant.parse("2026-12-01T09:00:00.789Z");
    private static final WorkshopId WORKSHOP = WorkshopId.newId();

    private final String privateJwk = TicketTestKeys.newPrivateJwk();
    private final NimbusEs256TicketSigner signer =
            new NimbusEs256TicketSigner(TicketSigningKey.parse(privateJwk));

    @Test
    @DisplayName("AC48.3: the header is exactly alg ES256, no typ and no kid")
    void header_isExactlyAlgEs256() throws Exception {
        var ticket = signer.issue(WORKSHOP, ISSUED);

        assertThat(part(ticket.qrPayload(), 0)).containsExactlyEntriesOf(Map.of("alg", "ES256"));
    }

    @Test
    @DisplayName("AC48.3: the payload is exactly ticket_code, workshop_id and iat: no exp, no PII")
    void payload_isExactlyThreeClaims() throws Exception {
        var ticket = signer.issue(WORKSHOP, ISSUED);

        var claims = part(ticket.qrPayload(), 1);

        assertThat(claims.keySet()).containsExactlyInAnyOrder("ticket_code", "workshop_id", "iat");
        assertThat(claims.get("ticket_code")).isEqualTo(ticket.code());
        assertThat(claims.get("workshop_id")).isEqualTo(WORKSHOP.value().toString());
        // NumericDate: whole seconds since the epoch, taken from the instant passed in
        assertThat(((Number) claims.get("iat")).longValue()).isEqualTo(ISSUED.getEpochSecond());
    }

    @Test
    @DisplayName("AC48.1: the ticket verifies with the published public key, alg pinned to ES256")
    void ticket_verifiesWithThePublicKey() throws Exception {
        var ticket = signer.issue(WORKSHOP, ISSUED);

        var jws = JWSObject.parse(ticket.qrPayload());

        assertThat(jws.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.ES256);
        assertThat(jws.verify(new ECDSAVerifier(publishedKey().toECPublicKey()))).isTrue();
    }

    @Test
    @DisplayName("a ticket does not verify under another key, or after its payload is touched")
    void ticket_doesNotVerifyElsewhereOrAfterTampering() throws Exception {
        var ticket = signer.issue(WORKSHOP, ISSUED);
        var other = new ECKeyGenerator(Curve.P_256).generate().toPublicJWK();
        var parts = ticket.qrPayload().split("\\.");
        var forgedClaims =
                Base64URL.encode(
                        "{\"ticket_code\":\"X\",\"workshop_id\":\"y\",\"iat\":1}"
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(
                        JWSObject.parse(ticket.qrPayload())
                                .verify(new ECDSAVerifier(other.toECPublicKey())))
                .isFalse();
        assertThat(
                        JWSObject.parse(parts[0] + "." + forgedClaims + "." + parts[2])
                                .verify(new ECDSAVerifier(publishedKey().toECPublicKey())))
                .isFalse();
    }

    @Test
    @DisplayName("a key that carries kid, use and alg still yields a header without a kid")
    void keyWithKid_stillSignsWithAPlainHeader() throws Exception {
        var withKid =
                new ECKeyGenerator(Curve.P_256)
                        .keyID("operator-chosen-id")
                        .keyUse(KeyUse.SIGNATURE)
                        .algorithm(JWSAlgorithm.ES256)
                        .generate();
        var kidSigner = new NimbusEs256TicketSigner(TicketSigningKey.parse(withKid.toJSONString()));

        var ticket = kidSigner.issue(WORKSHOP, ISSUED);

        assertThat(part(ticket.qrPayload(), 0).keySet()).containsExactly("alg");
    }

    @Test
    @DisplayName("each ticket has its own code and its own signature")
    void everyTicket_isDifferent() {
        var first = signer.issue(WORKSHOP, ISSUED);
        var second = signer.issue(WORKSHOP, ISSUED);

        assertThat(first.code()).isNotEqualTo(second.code());
        assertThat(first.qrPayload()).isNotEqualTo(second.qrPayload());
        assertThat(first.code()).hasSize(26);
    }

    @Test
    @DisplayName("AC47.2: the public key has kty, crv, x and y, built from the key's own x and y")
    void publicKey_hasExactlyTheFourMembers() throws ParseException {
        var published = signer.publicKey();
        var source = ECKey.parse(privateJwk);

        assertThat(published.kty()).isEqualTo("EC");
        assertThat(published.crv()).isEqualTo("P-256");
        assertThat(published.x()).isEqualTo(source.getX().toString());
        assertThat(published.y()).isEqualTo(source.getY().toString());
        assertThat(published.getClass().getRecordComponents()).hasSize(4);
        assertThat(published.toString()).doesNotContain(source.getD().toString());
    }

    @Test
    @DisplayName("a key with kid, use and alg publishes none of them")
    void publicKey_dropsEverythingButTheFourMembers() throws Exception {
        var withExtras =
                new ECKeyGenerator(Curve.P_256).keyID("kid-1").keyUse(KeyUse.SIGNATURE).generate();
        var extrasSigner =
                new NimbusEs256TicketSigner(TicketSigningKey.parse(withExtras.toJSONString()));

        var asJson = new ObjectMapper().writeValueAsString(extrasSigner.publicKey());

        assertThat(asJson).doesNotContain("kid").doesNotContain("use").doesNotContain("\"d\"");
        assertThat(new ObjectMapper().readValue(asJson, Map.class).keySet())
                .isEqualTo(Set.of("kty", "crv", "x", "y"));
    }

    private ECKey publishedKey() throws JOSEException {
        var published = signer.publicKey();
        return new ECKey.Builder(
                        Curve.P_256, new Base64URL(published.x()), new Base64URL(published.y()))
                .build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> part(String compact, int index) throws Exception {
        var json =
                new String(
                        Base64URL.from(compact.split("\\.")[index]).decode(),
                        StandardCharsets.UTF_8);
        return new ObjectMapper().readValue(json, Map.class);
    }
}
