package com.unihub.payment.web.controller;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aPaidWorkshop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.net.Webhook;
import com.unihub.payment.application.command.ChargePaymentCommand;
import com.unihub.payment.application.port.in.ChargePaymentUseCase;
import com.unihub.payment.application.port.out.ConfirmOutcome;
import com.unihub.payment.application.port.out.PaymentGateway;
import com.unihub.payment.application.result.ChargePaymentResult;
import com.unihub.registration.RegistrationFixtures;
import com.unihub.registration.application.command.ReserveSeatCommand;
import com.unihub.registration.application.port.in.ReserveSeatUseCase;
import com.unihub.registration.application.result.ReserveSeatResult;
import com.unihub.shared.application.port.out.IdempotencyStore;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.security.Actor;
import com.unihub.shared.security.TestJwts;
import com.unihub.shared.web.RequestFingerprint;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
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
 * What the webhook ITs share: a real PENDING payment with a gateway id (a charge whose confirm
 * answered "processing"), the signing secret, and signed or unsigned posts to the endpoint. Only
 * the gateway is doubled; the webhook itself runs through security, controller, verifier and
 * Postgres.
 */
abstract class PaymentWebhookITSupport extends IntegrationTestSupport {
    static final String SECRET = "webhook-secret-for-tests";
    static final String PATH = "/api/v1/webhooks/payment-gateway";
    private static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> TestJwts.SECRET);
        registry.add("unihub.payment.stripe.webhook.secret", () -> SECRET);
    }

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired WorkshopRepository workshops;
    @Autowired TransactionTemplate tx;
    @Autowired ReserveSeatUseCase reserveSeat;
    @Autowired ChargePaymentUseCase chargePayment;

    @MockBean PaymentGateway gateway;
    @SpyBean IdempotencyStore idempotency;

    RegistrationFixtures fixtures;

    /** A payment the gateway is still working on, as the webhook will find it. */
    record Pending(UserId student, UUID registration, UUID payment, String gatewayId, UUID key) {}

    @BeforeEach
    void setUpWebhook() {
        http.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        fixtures = new RegistrationFixtures(jdbc);
        reset(gateway, idempotency);
        when(gateway.create(any(), any(), any(), any()))
                .thenAnswer(call -> "pi_" + UUID.randomUUID());
        when(gateway.confirm(any(), any())).thenReturn(new ConfirmOutcome.Processing());
    }

    @AfterEach
    void cleanUpWebhook() {
        reset(gateway, idempotency);
        fixtures.cleanUp();
    }

    Pending aPendingPayment() {
        var workshop = aPaidWorkshop().withCapacity(5).build();
        tx.executeWithoutResult(s -> workshops.save(workshop));
        var workshopId = fixtures.track(workshop.id());
        var student = fixtures.aStudent();
        var actor = new Actor.UserActor(student, Set.of());
        var reserved =
                (ReserveSeatResult.Reserved)
                        reserveSeat.reserve(
                                new ReserveSeatCommand(
                                        actor,
                                        workshopId,
                                        UUID.randomUUID().toString(),
                                        RequestFingerprint.of(
                                                JSON, Map.of("workshopId", workshopId.value()))));
        var key = UUID.randomUUID();
        var processed =
                (ChargePaymentResult.Processed)
                        chargePayment.charge(
                                new ChargePaymentCommand(
                                        actor,
                                        reserved.id(),
                                        "pm_card_visa",
                                        key.toString(),
                                        "fingerprint"));
        assertThat(processed.status()).isEqualTo(ChargePaymentResult.Status.PENDING);
        return new Pending(
                student,
                reserved.id().value(),
                processed.paymentId(),
                processed.gatewayTransactionId(),
                key);
    }

    static String eventBody(String type, String gatewayId) {
        return "{\"id\":\"evt_"
                + UUID.randomUUID()
                + "\",\"type\":\""
                + type
                + "\",\"data\":{\"object\":{\"id\":\""
                + gatewayId
                + "\",\"object\":\"payment_intent\"}}}";
    }

    static String sign(String body) {
        return signAt(body, SECRET, Instant.now());
    }

    static String signAt(String body, String secret, Instant at) {
        try {
            return Webhook.Signature.generateSignatureHeader(body, secret, at.getEpochSecond());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** No Authorization header, as the gateway sends it. */
    ResponseEntity<String> post(String body, String signature) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (signature != null) {
            headers.add("Stripe-Signature", signature);
        }
        return http.exchange(PATH, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    String paymentStatus(UUID payment) {
        return jdbc.queryForObject(
                "SELECT status FROM payments WHERE id = ?", String.class, payment);
    }
}
