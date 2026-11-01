package com.unihub.checkin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.util.Base64URL;
import com.unihub.shared.security.TestJwts;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * M-48, with S-8 and S-12, on every push (Phase 5 checklist task 4.2; D47, D61 point 5; AC47.2):
 * {@code GET /api/v1/checkin/verification-key} over real HTTP, the real filter chain, the real
 * signer and the seeded roles. The key is public material only, only a holder of {@code
 * CHECKIN_SCAN} can fetch it, and it is the key that verifies a real ticket.
 *
 * <p>The application is started with an operator-supplied signing key that carries {@code kid},
 * {@code use} and {@code alg} (a key from a key-management tool often does) and, of course, the
 * private member {@code d}. Every case therefore runs where the response could leak any of them:
 * "exactly four members" is asserted against a key that has more.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class VerificationKeyIT extends CheckInITSupport {
    private static final String KEY_URL = "/api/v1/checkin/verification-key";
    private static final ECKey OPERATOR_KEY = operatorKey();

    @DynamicPropertySource
    static void operatorSuppliedSigningKey(DynamicPropertyRegistry registry) {
        registry.add("unihub.ticket.signing-key", OPERATOR_KEY::toJSONString);
    }

    private static ECKey operatorKey() {
        try {
            return new ECKeyGenerator(Curve.P_256)
                    .keyID("ops-2026-11")
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.ES256)
                    .generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- helpers ----------

    private ResponseEntity<String> fetch(String bearer) {
        var headers = new HttpHeaders();
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return http.exchange(KEY_URL, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private JsonNode fetchKey() throws Exception {
        var response = fetch(token(staff));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return JSON.readTree(response.getBody());
    }

    private static ECKey asPublicKey(JsonNode jwk) {
        return new ECKey.Builder(
                        Curve.P_256,
                        new Base64URL(jwk.get("x").asText()),
                        new Base64URL(jwk.get("y").asText()))
                .build();
    }

    /** A free registration over HTTP, which signs a real ticket; returns its {@code qrPayload}. */
    private String aRealTicket() throws Exception {
        var workshop = fixtures.aWorkshop();
        var student = fixtures.aStaffMember();
        var headers = new HttpHeaders();
        headers.setBearerAuth(TestJwts.valid(student.value(), "REGISTRATION_CREATE"));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("Idempotency-Key", UUID.randomUUID().toString());
        var response =
                http.exchange(
                        "/api/v1/registrations",
                        HttpMethod.POST,
                        new HttpEntity<>("{\"workshopId\":\"" + workshop.value() + "\"}", headers),
                        String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        var payload = JSON.readTree(response.getBody()).get("qrPayload").asText();
        assertThat(payload).isNotBlank();
        return payload;
    }

    private static boolean verifies(String compactJws, ECKey publicKey) throws Exception {
        return JWSObject.parse(compactJws).verify(new ECDSAVerifier(publicKey.toECPublicKey()));
    }

    // ---------- who may fetch the key (S-8) ----------

    @Test
    @DisplayName("S-8: a Check-in Staff member, logged in for real, gets the key")
    void checkInStaff_getsTheKey() throws Exception {
        var response = fetch(loginAs("Check-in Staff"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType().toString())
                .startsWith(MediaType.APPLICATION_JSON_VALUE);
    }

    @Test
    @DisplayName("S-8: Student, Organizer and Admin, logged in for real, are refused with 403")
    void everyOtherSeededRole_isRefused() throws Exception {
        for (var role : List.of("Student", "Organizer", "Admin")) {
            var response = fetch(loginAs(role));

            assertThat(response.getStatusCode().value()).as(role).isEqualTo(403);
            assertThat(response.getBody())
                    .as(role + ": nothing of the key leaks")
                    .doesNotContain(OPERATOR_KEY.getX().toString());
        }
    }

    @Test
    @DisplayName("S-8: no token, an expired token, a tampered token, a wrong-secret token: 401")
    void noValidToken_is401() {
        var id = UUID.randomUUID();
        var tampered =
                TestJwts.withPayload(
                        TestJwts.valid(id, "REGISTRATION_CREATE"),
                        "{\"sub\":\"%s\",\"permissions\":[\"CHECKIN_SCAN\"]}".formatted(id));
        var wrongSecret =
                TestJwts.sign(
                        JWSAlgorithm.HS256,
                        "another-secret-another-secret-another-secret-another-secret-0123",
                        TestJwts.claims(
                                id,
                                List.of("CHECKIN_SCAN"),
                                java.time.Instant.now(),
                                java.time.Instant.now().plus(Duration.ofMinutes(5))));

        Stream.of(
                        null,
                        TestJwts.expired(id, Duration.ofMinutes(5), "CHECKIN_SCAN"),
                        tampered,
                        wrongSecret,
                        "not-a-jwt")
                .forEach(
                        bearer ->
                                assertThat(fetch(bearer).getStatusCode().value())
                                        .as("bearer: " + bearer)
                                        .isEqualTo(401));
    }

    @Test
    @DisplayName("S-8: the decision follows the permission, not a role name")
    void permissionDecides_notTheRoleName() {
        var id = UUID.randomUUID();
        var claimsARole = TestJwts.withClaims(id, Map.of("role", "Check-in Staff"));

        assertThat(fetch(TestJwts.valid(id, "CHECKIN_SCAN")).getStatusCode().value())
                .as("the permission alone is enough")
                .isEqualTo(200);
        assertThat(fetch(claimsARole).getStatusCode().value())
                .as("a role claim without the permission is nothing")
                .isEqualTo(403);
        assertThat(
                        fetch(TestJwts.valid(id, "REPORT_VIEW", "WORKSHOP_EDIT"))
                                .getStatusCode()
                                .value())
                .as("other permissions are not it")
                .isEqualTo(403);
    }

    @Test
    @DisplayName("401 and 403 are RFC 7807 problems, and refuse before saying anything of the key")
    void refusals_areProblemJson() {
        var unauthorised = fetch(null);
        var forbidden = fetch(TestJwts.valid(UUID.randomUUID(), "REGISTRATION_CREATE"));

        for (var refusal : List.of(unauthorised, forbidden)) {
            var type = refusal.getHeaders().getContentType();
            assertThat(type).isNotNull();
            assertThat(type.toString()).startsWith("application/problem+json");
            assertThat(refusal.getBody()).doesNotContain("\"x\"").doesNotContain("\"kty\"");
        }
    }

    // ---------- what the key contains (S-12, AC47.2) ----------

    @Test
    @DisplayName("S-12: exactly kty, crv, x, y: no d, and no kid/use/alg of the operator's key")
    void response_hasExactlyTheFourPublicMembers() throws Exception {
        var response = fetch(token(staff));

        var key = JSON.readTree(response.getBody());
        var members = new java.util.ArrayList<String>();
        key.fieldNames().forEachRemaining(members::add);
        assertThat(members).containsExactlyInAnyOrder("kty", "crv", "x", "y");
        assertThat(key.get("kty").asText()).isEqualTo("EC");
        assertThat(key.get("crv").asText()).isEqualTo("P-256");
        assertThat(key.get("x").asText()).isEqualTo(OPERATOR_KEY.getX().toString());
        assertThat(key.get("y").asText()).isEqualTo(OPERATOR_KEY.getY().toString());
        assertThat(OPERATOR_KEY.getKeyID())
                .as("the fixture really is a key with extras")
                .isNotNull();
        assertThat(response.getBody())
                .as("the private member and the operator's labels appear nowhere in the body")
                .doesNotContain(OPERATOR_KEY.getD().toString())
                .doesNotContain(OPERATOR_KEY.getKeyID())
                .doesNotContain("\"kid\"")
                .doesNotContain("\"use\"")
                .doesNotContain("\"alg\"")
                .doesNotContain("\"d\"");
    }

    @Test
    @DisplayName("S-12: the key that comes back cannot sign: a device that can verify cannot forge")
    void returnedKey_cannotSign() throws Exception {
        var received = ECKey.parse(fetchKey().toString());

        assertThat(received.isPrivate()).isFalse();
        assertThatThrownBy(() -> new ECDSASigner(received)).isInstanceOf(JOSEException.class);
    }

    // ---------- the key is the right key ----------

    @Test
    @DisplayName("AC47.2: a real ticket issued over HTTP verifies under the fetched key, ES256")
    void realTicket_verifiesUnderTheFetchedKey() throws Exception {
        var ticket = aRealTicket();

        var jws = JWSObject.parse(ticket);
        assertThat(jws.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.ES256);
        assertThat(verifies(ticket, asPublicKey(fetchKey()))).isTrue();
    }

    @Test
    @DisplayName("the fetched key tells a real ticket from a tampered one and from another key's")
    void fetchedKey_rejectsTamperedAndForeignTickets() throws Exception {
        var ticket = aRealTicket();
        var key = asPublicKey(fetchKey());
        var parts = ticket.split("\\.");
        var otherPayload =
                Base64URL.encode(
                        "{\"ticket_code\":\"FORGED\",\"workshop_id\":\"%s\",\"iat\":1}"
                                .formatted(UUID.randomUUID()));
        var tampered = parts[0] + "." + otherPayload + "." + parts[2];
        var foreign = signedBy(new ECKeyGenerator(Curve.P_256).generate(), JWSObject.parse(ticket));

        assertThat(verifies(ticket, key)).as("the real ticket").isTrue();
        assertThat(verifies(tampered, key)).as("same signature, other payload").isFalse();
        assertThat(verifies(foreign, key)).as("signed by a key that is not ours").isFalse();
    }

    private static String signedBy(ECKey other, JWSObject like) throws Exception {
        var forged = new JWSObject(like.getHeader(), like.getPayload());
        forged.sign(new ECDSASigner(other));
        return forged.serialize();
    }

    @Test
    @DisplayName("one active key: two fetches return the same key")
    void twoFetches_returnTheSameKey() throws Exception {
        assertThat(fetchKey()).isEqualTo(fetchKey());
    }
}
