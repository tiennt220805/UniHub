package com.unihub.registration;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aPaidWorkshop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.payment.application.command.ChargePaymentCommand;
import com.unihub.payment.application.port.in.ChargePaymentUseCase;
import com.unihub.payment.application.port.out.ConfirmOutcome;
import com.unihub.payment.application.port.out.PaymentGateway;
import com.unihub.payment.application.result.ChargePaymentResult;
import com.unihub.registration.application.command.ReserveSeatCommand;
import com.unihub.registration.application.port.in.ReserveSeatUseCase;
import com.unihub.registration.application.result.ReserveSeatResult;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.infrastructure.out.messaging.Topology;
import com.unihub.shared.security.Actor;
import com.unihub.shared.security.TestJwts;
import com.unihub.shared.web.RequestFingerprint;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What the two consumers' ITs share (checklist D2, T-CONF): a paid registration with a payment that
 * SUCCEEDED, built through the real use cases with only the gateway doubled, and messages put on
 * the real exchange for the real listeners to take. The relay stays off (see
 * IntegrationTestSupport), so the test is the relay: an outbox row it reads is the message it then
 * publishes. Subclasses start the listeners for their own context and must close it afterwards
 * ({@code @DirtiesContext}).
 */
public abstract class HandOffITSupport extends IntegrationTestSupport {
    private static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> TestJwts.SECRET);
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "true");
    }

    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected WorkshopRepository workshops;
    @Autowired protected TransactionTemplate tx;
    @Autowired protected ReserveSeatUseCase reserveSeat;
    @Autowired protected ChargePaymentUseCase chargePayment;
    @Autowired protected RabbitTemplate rabbit;
    @Autowired protected AmqpAdmin amqpAdmin;

    @MockBean protected PaymentGateway gateway;

    protected RegistrationFixtures fixtures;

    /** A paid registration and the payment that took its money. */
    public record Paid(
            UserId student, UUID workshop, UUID registration, UUID payment, String gatewayId) {}

    @BeforeEach
    void setUpHandOff() {
        fixtures = new RegistrationFixtures(jdbc);
        reset(gateway);
        when(gateway.create(any(), any(), any(), any()))
                .thenAnswer(call -> "pi_" + UUID.randomUUID());
        when(gateway.confirm(any(), any())).thenReturn(new ConfirmOutcome.Succeeded());
    }

    @AfterEach
    void cleanUpHandOff() {
        fixtures.cleanUp();
    }

    /**
     * RESERVED registration + SUCCEEDED payment, as when the success has not yet been announced.
     */
    protected Paid aPaidRegistration() {
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
        var processed =
                (ChargePaymentResult.Processed)
                        chargePayment.charge(
                                new ChargePaymentCommand(
                                        actor,
                                        reserved.id(),
                                        "pm_card_visa",
                                        UUID.randomUUID().toString(),
                                        "fingerprint"));
        assertThat(processed.status()).isEqualTo(ChargePaymentResult.Status.SUCCEEDED);
        return new Paid(
                student,
                workshopId.value(),
                reserved.id().value(),
                processed.paymentId(),
                processed.gatewayTransactionId());
    }

    /** What the relay would publish for a payment's success. */
    protected void publishChargeSucceeded(Paid paid) {
        publish(
                "unihub.payment.charge.succeeded",
                "{\"paymentId\":\""
                        + paid.payment()
                        + "\",\"registrationId\":\""
                        + paid.registration()
                        + "\",\"occurredAt\":\"2026-12-01T09:00:00Z\",\"schemaVersion\":1}");
    }

    protected void publish(String routingKey, String json) {
        var properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setMessageId(UUID.randomUUID().toString());
        rabbit.send(
                Topology.EXCHANGE,
                routingKey,
                MessageBuilder.withBody(json.getBytes(StandardCharsets.UTF_8))
                        .andProperties(properties)
                        .build());
    }

    protected String registrationStatus(UUID registration) {
        return jdbc.queryForObject(
                "SELECT status FROM registrations WHERE id = ?", String.class, registration);
    }

    protected String paymentStatus(UUID payment) {
        return jdbc.queryForObject(
                "SELECT status FROM payments WHERE id = ?", String.class, payment);
    }

    protected long paymentVersion(UUID payment) {
        return jdbc.queryForObject(
                "SELECT version FROM payments WHERE id = ?", Long.class, payment);
    }

    protected int outboxRows(UUID registration, String routingKey) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM event_publications WHERE aggregate_id = ? AND routing_key = ?",
                Integer.class,
                registration,
                routingKey);
    }

    /** The payloads of the rows with this key, oldest first: what the relay would publish. */
    protected List<String> outboxPayloads(UUID registration, String routingKey) {
        return jdbc.queryForList(
                "SELECT payload::text FROM event_publications WHERE aggregate_id = ? AND"
                        + " routing_key = ? ORDER BY created_at",
                String.class,
                registration,
                routingKey);
    }

    /** Ends the registration as the hold expiry (Phase 3) or a cancellation would. */
    protected void closeTheRegistration(UUID registration, String status) {
        jdbc.update(
                "UPDATE registrations SET status = ?, hold_expires_at = NULL, version = version + 1"
                        + " WHERE id = ?",
                status,
                registration);
        jdbc.update(
                "UPDATE workshops SET seats_taken = seats_taken - 1, version = version + 1 WHERE id ="
                        + " (SELECT workshop_id FROM registrations WHERE id = ?)",
                registration);
    }
}
