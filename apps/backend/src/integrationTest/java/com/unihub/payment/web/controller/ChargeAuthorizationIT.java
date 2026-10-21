package com.unihub.payment.web.controller;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aPaidWorkshop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import com.unihub.payment.application.port.out.PaymentGateway;
import com.unihub.registration.RegistrationFixtures;
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
import org.springframework.boot.test.mock.mockito.MockBean;
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
 * T-RBAC for the charge (D7, AC7.1): authorization is by permission and is decided before the use
 * case runs, so a refusal records no idempotency key and calls no gateway. Ownership (AC7.3) is in
 * {@code PaymentControllerIT#otherUsersRegistration_is403}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChargeAuthorizationIT extends IntegrationTestSupport {
    private static final String PATH = "/api/v1/payments/charge";

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

    @MockBean private PaymentGateway gateway;

    private RegistrationFixtures fixtures;

    @BeforeEach
    void setUp() {
        http.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        fixtures = new RegistrationFixtures(jdbc);
        reset(gateway);
    }

    @AfterEach
    void cleanUp() {
        fixtures.cleanUp();
    }

    private int post(String token, UUID registration) {
        var headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("Idempotency-Key", UUID.randomUUID().toString());
        var body = "{\"registrationId\":\"" + registration + "\",\"paymentMethodToken\":\"pm_x\"}";
        return http.exchange(PATH, HttpMethod.POST, new HttpEntity<>(body, headers), String.class)
                .getStatusCode()
                .value();
    }

    @Test
    @DisplayName(
            "without REGISTRATION_CREATE: no token is 401, a token without it is 403, nothing recorded")
    void withoutRegistrationCreate_is403_nothingRecorded() {
        var workshop = aPaidWorkshop().withCapacity(5).build();
        tx.executeWithoutResult(s -> workshops.save(workshop));
        fixtures.track(workshop.id());
        var student = fixtures.aStudent();
        var registration = UUID.randomUUID();

        assertThat(post(null, registration)).isEqualTo(401);
        assertThat(post(TestJwts.valid(student.value()), registration)).isEqualTo(403);
        assertThat(post(TestJwts.valid(student.value(), "WORKSHOP_CREATE"), registration))
                .isEqualTo(403);

        assertThat(fixtures.chargeKeys(student)).isZero();
        verify(gateway, never()).create(any(), any(), any(), any());
    }
}
