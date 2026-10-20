package com.unihub.registration.web.controller;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aPublishedWorkshop;
import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.registration.RegistrationFixtures;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.security.TestJwts;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * RBAC on {@code createRegistration} (D7): the permission decides, never a role, and a refused
 * caller leaves no effect. The idempotency-key half of AC7.1 ("no key recorded") joins this class
 * with checklist 3.3.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RegistrationAuthorizationIT extends IntegrationTestSupport {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> TestJwts.SECRET);
    }

    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private WorkshopRepository workshops;
    @Autowired private TransactionTemplate tx;

    private RegistrationFixtures fixtures;

    @BeforeEach
    void useAClientThatCanReadA401() {
        // The default HttpURLConnection factory cannot retry an authenticated streamed POST.
        http.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

    @AfterEach
    void cleanUp() {
        if (fixtures != null) {
            fixtures.cleanUp();
        }
    }

    private int post(String token, WorkshopId workshop) {
        var headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("Idempotency-Key", UUID.randomUUID().toString());
        var body = "{\"workshopId\":\"" + workshop.value() + "\"}";
        return http.exchange(
                        "/api/v1/registrations",
                        HttpMethod.POST,
                        new HttpEntity<>(body, headers),
                        String.class)
                .getStatusCode()
                .value();
    }

    @Test
    @DisplayName(
            "without REGISTRATION_CREATE: no token is 401, a token without it is 403, no effect")
    void withoutRegistrationCreate_isRefused_noEffect() {
        fixtures = new RegistrationFixtures(jdbc);
        var workshop = aPublishedWorkshop().withCapacity(5).build();
        tx.executeWithoutResult(s -> workshops.save(workshop));
        fixtures.track(workshop.id());
        var user = UUID.randomUUID();

        assertThat(post(null, workshop.id())).isEqualTo(401);
        assertThat(post(TestJwts.valid(user), workshop.id())).isEqualTo(403);
        assertThat(post(TestJwts.valid(user, "WORKSHOP_CREATE", "WORKSHOP_EDIT"), workshop.id()))
                .isEqualTo(403);

        assertThat(fixtures.seatsTaken(workshop.id())).isZero();
        assertThat(fixtures.registrationRows(workshop.id())).isZero();
    }
}
