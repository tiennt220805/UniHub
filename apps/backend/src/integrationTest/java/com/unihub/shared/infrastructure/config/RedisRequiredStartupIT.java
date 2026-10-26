package com.unihub.shared.infrastructure.config;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aPublishedWorkshop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unihub.UniHubApplication;
import com.unihub.registration.RegistrationFixtures;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.security.TestJwts;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Redis is required configuration (checklist D21): AC21.1 a missing address stops the application
 * at start with a clear message; AC21.2 an address that is set but unreachable does not — the
 * application starts and refuses a registration with 503 instead of proceeding uncoordinated.
 *
 * <p>Readiness staying {@code UP} with Redis down (AC21.2, observability.md §5) cannot be asserted
 * yet: there is no actuator or readiness endpoint in the Backend API, so the assertion arrives with
 * the observability work.
 */
class RedisRequiredStartupIT extends IntegrationTestSupport {

    @Test
    @DisplayName("AC21.1: no Redis address, no start, and the message says what to set")
    void missingAddress_stopsTheApplication() {
        // a command-line argument outranks the system property IntegrationTestSupport sets
        var application =
                new SpringApplicationBuilder(UniHubApplication.class)
                        .web(WebApplicationType.NONE)
                        .properties(
                                "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                                "spring.datasource.username=" + POSTGRES.getUsername(),
                                "spring.datasource.password=" + POSTGRES.getPassword(),
                                "unihub.security.jwt.secret=" + TestJwts.SECRET);

        assertThatThrownBy(() -> application.run("--unihub.redis.address="))
                .hasStackTraceContaining("unihub.redis.address is required")
                .hasStackTraceContaining("UNIHUB_REDIS_ADDRESS");
    }

    /** Own context: the address points at a port nothing listens on. */
    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    class UnreachableRedis {

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
            registry.add("unihub.security.jwt.secret", () -> TestJwts.SECRET);
            registry.add("unihub.redis.address", () -> "redis://127.0.0.1:1");
        }

        @Autowired private TestRestTemplate http;
        @Autowired private JdbcTemplate jdbc;
        @Autowired private WorkshopRepository workshops;
        @Autowired private TransactionTemplate tx;

        @Test
        @DisplayName("AC21.2: the application is up, and a registration is refused with 503")
        void startsAndRefusesRegistration() {
            var fixtures = new RegistrationFixtures(jdbc);
            try {
                var workshop = aPublishedWorkshop().withCapacity(5).build();
                tx.executeWithoutResult(s -> workshops.save(workshop));
                fixtures.track(workshop.id());
                var student = fixtures.aStudent();
                var headers = new HttpHeaders();
                headers.setBearerAuth(TestJwts.valid(student.value(), "REGISTRATION_CREATE"));
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.add("Idempotency-Key", UUID.randomUUID().toString());
                var body = "{\"workshopId\":\"" + workshop.id().value() + "\"}";

                var response =
                        http.exchange(
                                "/api/v1/registrations",
                                HttpMethod.POST,
                                new HttpEntity<>(body, headers),
                                String.class);

                assertThat(response.getStatusCode().value()).isEqualTo(503);
                assertThat(response.getHeaders().getFirst("Retry-After")).isNotNull();
                assertThat(fixtures.seatsTaken(workshop.id())).isZero();
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT count(*) FROM registrations WHERE workshop_id = ?",
                                        Integer.class,
                                        workshop.id().value()))
                        .isZero();
            } finally {
                fixtures.cleanUp();
            }
        }
    }
}
