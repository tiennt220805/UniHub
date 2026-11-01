package com.unihub.checkin;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.security.TestJwts;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What the Check-in ITs share: real HTTP against the real application and Postgres, a staff member
 * with {@code CHECKIN_SCAN}, tickets in the database, and the sync request built by hand so a test
 * can put anything in it. Everything a test creates is committed (reconciliation is a property of
 * real transactions) and removed by {@link #cleanUpCheckIn}.
 */
public abstract class CheckInITSupport extends IntegrationTestSupport {
    protected static final ObjectMapper JSON = new ObjectMapper();
    protected static final Instant T0 = Instant.parse("2026-12-01T09:00:00Z");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> TestJwts.SECRET);
    }

    @Autowired protected TestRestTemplate http;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected WorkshopRepository workshops;
    @Autowired protected TransactionTemplate tx;

    protected CheckInFixtures fixtures;
    protected UserId staff;

    @BeforeEach
    void setUpCheckIn() {
        http.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        fixtures = new CheckInFixtures(jdbc, workshops, tx);
        staff = fixtures.aStaffMember();
    }

    @AfterEach
    void cleanUpCheckIn() {
        fixtures.cleanUp();
    }

    /** One scan of a request, as the device would write it. */
    public record Scan(
            UUID id,
            String verdict,
            String ticketCode,
            UUID venue,
            Instant scannedAt,
            String deviceId) {

        public String json() {
            return "{\"id\":\"%s\",\"deviceVerdict\":\"%s\",\"ticketCode\":%s,\"workshopId\":\"%s\","
                            .formatted(id, verdict, ticketCode == null ? "null" : ticketCode, venue)
                    + "\"scannedAt\":\"%s\",\"deviceId\":\"%s\"}".formatted(scannedAt, deviceId);
        }

        public Scan at(Instant other) {
            return new Scan(id, verdict, ticketCode, venue, other, deviceId);
        }

        public Scan onDevice(String device) {
            return new Scan(id, verdict, ticketCode, venue, scannedAt, device);
        }
    }

    protected static Scan verified(CheckInFixtures.Ticket ticket, Instant at, String device) {
        return new Scan(
                UUID.randomUUID(),
                "VERIFIED",
                "\"" + ticket.code() + "\"",
                ticket.workshopId().value(),
                at,
                device);
    }

    /** A scan the device rejected; {@code rawCode} is pasted into the JSON as it is. */
    protected static Scan rejectedBy(WorkshopId venue, Instant at, String rawCode) {
        return new Scan(
                UUID.randomUUID(), "REJECTED_SIGNATURE", rawCode, venue.value(), at, "dev-1");
    }

    protected static String batch(List<Scan> scans) {
        return "{\"scans\":[" + String.join(",", scans.stream().map(Scan::json).toList()) + "]}";
    }

    protected ResponseEntity<String> sync(UserId caller, Scan... scans) {
        return syncBody(token(caller), batch(List.of(scans)));
    }

    protected ResponseEntity<String> syncAs(UserId caller, List<Scan> scans) {
        return syncBody(token(caller), batch(scans));
    }

    protected ResponseEntity<String> syncBody(String bearer, String body) {
        var headers = new HttpHeaders();
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(
                "/api/v1/checkin/sync",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class);
    }

    protected static String token(UserId caller) {
        return TestJwts.valid(caller.value(), "CHECKIN_SCAN");
    }

    /** The outcome per scan id of a 200 response. */
    protected static Map<UUID, String> outcomes(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(200);
        JsonNode results = JSON.readTree(response.getBody()).get("results");
        var byId = new HashMap<UUID, String>();
        results.forEach(
                r -> byId.put(UUID.fromString(r.get("id").asText()), r.get("outcome").asText()));
        return byId;
    }

    protected String storedOutcome(UUID scan) {
        return jdbc.queryForObject(
                "SELECT outcome FROM check_in_scans WHERE id = ?", String.class, scan);
    }

    protected int scanRows(UUID registration) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM check_in_scans WHERE registration_id = ?",
                Integer.class,
                registration);
    }

    protected int attendanceRows(UUID registration) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM attendances WHERE registration_id = ?",
                Integer.class,
                registration);
    }

    protected UUID winningScan(UUID registration) {
        return jdbc.queryForObject(
                "SELECT winning_scan_id FROM attendances WHERE registration_id = ?",
                UUID.class,
                registration);
    }

    protected int scansOf(UserId caller) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM check_in_scans WHERE scanned_by = ?",
                Integer.class,
                caller.value());
    }
}
