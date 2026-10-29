package com.unihub.payment.infrastructure.in.messaging;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aPaidWorkshop;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.unihub.payment.application.command.RefundPaymentCommand;
import com.unihub.payment.application.port.in.RefundPaymentUseCase;
import com.unihub.registration.RegistrationFixtures;
import com.unihub.shared.domain.OptimisticConflictException;
import com.unihub.shared.domain.id.PaymentId;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.infrastructure.Redeliveries;
import com.unihub.shared.security.TestJwts;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * INV-11 for the refund consumer (Phase 4 checklist D41 (7), AC41.1, M-40): the same refund job
 * handled by two threads at once, through the real Stripe adapter against a WireMock stand-in. What
 * the stand-in is asked is what counts: <b>the number of distinct {@code Idempotency-Key}s it
 * received is one</b>, however many requests arrived. Stripe is what turns two requests with one
 * key into one refund, so the stand-in does not imitate that and answers every request plainly; the
 * guarantee that UniHub owns is that the key is fixed by the payment and the step.
 *
 * <p>The sequential half is {@code RefundIT} ({@code refunded_isNoOp}: later deliveries never touch
 * the gateway). A losing call may fail only with the version race; after a failure the call is made
 * once more, standing for the retry hop, and must not reach the gateway at all.
 */
// Its gateway points at a WireMock this class stops, and its pool would stay open for the JVM.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest
class RefundConcurrentRedeliveryIT extends IntegrationTestSupport {
    private static final String REFUNDS = "/v1/refunds";
    private static final int ROUNDS = 4;

    private static WireMockServer stripe;

    @BeforeAll
    static void startStripe() {
        stripe = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        stripe.start();
    }

    @AfterAll
    static void stopStripe() {
        stripe.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> TestJwts.SECRET);
        registry.add("unihub.payment.stripe.secret-key", () -> "sk_test_dummy_for_wiremock");
        registry.add("unihub.payment.stripe.api-base", () -> "http://localhost:" + stripe.port());
    }

    @Autowired private RefundPaymentUseCase refundPayment;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private WorkshopRepository workshops;
    @Autowired private TransactionTemplate tx;

    private RegistrationFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new RegistrationFixtures(jdbc);
        stripe.resetAll();
        // nothing refunded yet; every create answers succeeded; neither imitates Stripe's keys
        stripe.stubFor(
                get(urlPathEqualTo(REFUNDS))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                "{\"object\":\"list\",\"url\":\"/v1/refunds\","
                                                        + "\"has_more\":false,\"data\":[]}")));
        stripe.stubFor(
                post(urlEqualTo(REFUNDS))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                "{\"id\":\"re_1\",\"object\":\"refund\","
                                                        + "\"payment_intent\":\"pi_x\","
                                                        + "\"status\":\"succeeded\"}")));
    }

    @AfterEach
    void cleanUp() {
        fixtures.cleanUp();
    }

    /** A payment the gateway holds money for and that is owed back: {@code REFUND_REQUIRED}. */
    private UUID anOwedPayment() {
        var workshop = aPaidWorkshop().withCapacity(5).build();
        tx.executeWithoutResult(s -> workshops.save(workshop));
        fixtures.track(workshop.id());
        var student = fixtures.aStudent();
        var registration = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO registrations (id, workshop_id, user_id, ticket_type, status) VALUES"
                        + " (?, ?, ?, 'PAID', 'EXPIRED')",
                registration,
                workshop.id().value(),
                student.value());
        var payment = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO payments (id, registration_id, amount, gateway_transaction_id, status)"
                        + " VALUES (?, ?, 100000, ?, 'REFUND_REQUIRED')",
                payment,
                registration,
                "pi_" + UUID.randomUUID());
        return payment;
    }

    private long version(UUID payment) {
        return jdbc.queryForObject(
                "SELECT version FROM payments WHERE id = ?", Long.class, payment);
    }

    private String status(UUID payment) {
        return jdbc.queryForObject(
                "SELECT status FROM payments WHERE id = ?", String.class, payment);
    }

    @Test
    @DisplayName(
            "payment-refund-execution: the same refund job handled by two threads at once reaches"
                    + " the gateway under one idempotency key and refunds once")
    void sameRefundJob_fromTwoThreads_usesOneIdempotencyKey() {
        for (int round = 0; round < ROUNDS; round++) {
            stripe.resetRequests();
            var payment = anOwedPayment();
            var versionBefore = version(payment);
            var command = new RefundPaymentCommand(PaymentId.of(payment));

            var outcome =
                    Redeliveries.concurrently(2, () -> refundPayment.refund(command))
                            .assertOnlyFailuresOf(OptimisticConflictException.class);

            assertThat(status(payment)).as("round " + round).isEqualTo("REFUNDED");
            assertThat(version(payment))
                    .as("one conditional transition")
                    .isEqualTo(versionBefore + 1);
            var creates = stripe.findAll(postRequestedFor(urlEqualTo(REFUNDS)));
            assertThat(creates).as("the gateway was asked to create").isNotEmpty();
            assertThat(creates.stream().map(r -> r.getHeader("Idempotency-Key")).distinct())
                    .as("however many requests, one key: fixed by payment and step")
                    .hasSize(1);
            var asked = stripe.getAllServeEvents().size();
            if (outcome.anyFailed()) {
                refundPayment.refund(command); // the retry hop
            }
            assertThat(stripe.getAllServeEvents())
                    .as("a job for a payment already REFUNDED never reaches the gateway")
                    .hasSize(asked);
        }
    }
}
