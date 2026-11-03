package com.unihub.shared.infrastructure.correlation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.payment.application.port.out.ConfirmOutcome;
import com.unihub.payment.application.port.out.GatewayPaymentState;
import com.unihub.registration.ShortChainITSupport;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.security.TestJwts;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * M-53 (Phase 6 checklist D65-D67, 3.4, 3.6; REQ-3.1.1): one correlation id from the HTTP request
 * through the transactional outbox and RabbitMQ to the log lines of the consumers, and through more
 * than one asynchronous step. Real HTTP, the real relay, the real listeners on a broker of their
 * own, and only the payment gateway doubled.
 *
 * <p>The chain of a paid registration: the {@code charge} request (id C) writes {@code
 * charge.succeeded} with C; the relay publishes it with C in the {@code correlationId} header; the
 * payment-confirmation consumer logs under C and, in its transaction, writes {@code
 * ticket.confirmed} with C; the relay publishes that; the notification consumer logs under C. The
 * hold-expiry job, which travels through the delay queue and back, carries the id of the
 * registration request.
 *
 * <p>The values are read where they are written, not inferred: the response header, the {@code
 * event_publications.correlation_id} column, the id each listener logged on receiving the message
 * (which is the header's value) and the MDC of log events the consumers produced. The subclasses
 * differ only in the sampling probability: with 0.0 nothing is exported, and every id must still be
 * there (D66; if it were not, the work would stop for a decision, not fall back to a UUID).
 */
public abstract class CorrelationPropagationITBase extends ShortChainITSupport {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration LONG_ENOUGH = Duration.ofSeconds(60);
    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern RECEIVED = Pattern.compile("correlationId=(\\S+)$");

    @DynamicPropertySource
    static void propagationProperties(DynamicPropertyRegistry registry) {
        registry.add("unihub.jobs.outbox-relay.enabled", () -> "true");
        registry.add("unihub.jobs.outbox-relay.interval", () -> "PT0.2S");
        registry.add("unihub.jobs.payment-reconciliation.enabled", () -> "true");
        registry.add("unihub.jobs.payment-reconciliation.interval", () -> "PT0.5S");
        registry.add("unihub.payment.reconciliation.stale-after", () -> "PT1S");
    }

    @Autowired private TestRestTemplate http;

    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private final List<UUID> students = new ArrayList<>();

    @BeforeEach
    void captureLogs() {
        jdbc.update("DELETE FROM event_publications");
        logged.start();
        root.addAppender(logged);
    }

    /**
     * Lets the hold jobs run their course while their registrations exist, as the notification ITs
     * do, and drops what the tests created.
     */
    @AfterEach
    void settleAndForget() {
        root.detachAppender(logged);
        await().atMost(LONG_ENOUGH)
                .during(Duration.ofMillis(700))
                .until(
                        () ->
                                waiting(
                                                        com.unihub.shared.infrastructure.out
                                                                .messaging.Topology
                                                                .HOLD_DELAY_QUEUE)
                                                == 0
                                        && waiting("unihub.seat-hold-expiry.queue") == 0);
        students.forEach(user -> jdbc.update("DELETE FROM notifications WHERE user_id = ?", user));
        students.clear();
    }

    // ---------- HTTP ----------

    private ResponseEntity<String> post(String path, UserId caller, String body) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(TestJwts.valid(caller.value(), "REGISTRATION_CREATE"));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("Idempotency-Key", UUID.randomUUID().toString());
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private static String correlationHeader(ResponseEntity<String> response) {
        var id = response.getHeaders().getFirst("X-Correlation-Id");
        assertThat(id).as("X-Correlation-Id of %s", response.getStatusCode()).matches(TRACE_ID);
        return id;
    }

    /** What one paid registration did, and the ids its requests were answered with. */
    private record Flow(
            UserId student, UUID registration, UUID payment, String registerId, String chargeId) {}

    private Flow registerAndPay(ConfirmOutcome outcome) throws Exception {
        when(gateway.confirm(any(), any())).thenReturn(outcome);
        WorkshopId workshop = aWorkshop(5);
        var student = fixtures.aStudent();
        students.add(student.value());
        var registered =
                post(
                        "/api/v1/registrations",
                        student,
                        "{\"workshopId\":\"" + workshop.value() + "\"}");
        assertThat(registered.getStatusCode().value()).as(registered.getBody()).isEqualTo(201);
        var registration = UUID.fromString(JSON.readTree(registered.getBody()).get("id").asText());
        var charged =
                post(
                        "/api/v1/payments/charge",
                        student,
                        "{\"registrationId\":\"%s\",\"paymentMethodToken\":\"pm_card_visa\"}"
                                .formatted(registration));
        assertThat(charged.getStatusCode().is2xxSuccessful()).as(charged.getBody()).isTrue();
        var payment = UUID.fromString(JSON.readTree(charged.getBody()).get("id").asText());
        return new Flow(
                student,
                registration,
                payment,
                correlationHeader(registered),
                correlationHeader(charged));
    }

    // ---------- what was written ----------

    private List<String> storedIds(String routingKey, UUID registration) {
        return jdbc.queryForList(
                "SELECT correlation_id FROM event_publications WHERE routing_key = ? AND"
                        + " payload ->> 'registrationId' = ?",
                String.class,
                routingKey,
                registration.toString());
    }

    private List<String> storedHoldIds(UUID registration) {
        return jdbc.queryForList(
                "SELECT correlation_id FROM event_publications WHERE routing_key ="
                        + " 'unihub.registration.hold.reserved' AND payload ->> 'registrationId' = ?",
                String.class,
                registration.toString());
    }

    private List<ILoggingEvent> events() {
        synchronized (logged) {
            return new ArrayList<>(logged.list);
        }
    }

    /** The ids the listeners of {@code queue} logged on receiving a message: the header values. */
    private Set<String> receivedOn(String queue) {
        var ids = new TreeSet<String>();
        for (var event : events()) {
            if (!event.getLoggerName().endsWith("CorrelationIdListenerAdvice")
                    || !event.getFormattedMessage().contains("queue=" + queue + " ")) {
                continue;
            }
            var matcher = RECEIVED.matcher(event.getFormattedMessage());
            assertThat(matcher.find()).as(event.getFormattedMessage()).isTrue();
            assertThat(event.getMDCPropertyMap().get("correlationId"))
                    .as("the MDC of the delivery is the header's value")
                    .isEqualTo(matcher.group(1));
            ids.add(matcher.group(1));
        }
        return ids;
    }

    /** Ids on log events written by this class's code while a consumer was running. */
    private Set<String> loggedBy(String loggerSuffix) {
        var ids = new TreeSet<String>();
        for (var event : events()) {
            if (event.getLoggerName().endsWith(loggerSuffix)
                    && event.getMDCPropertyMap().get("correlationId") != null) {
                ids.add(event.getMDCPropertyMap().get("correlationId"));
            }
        }
        return ids;
    }

    private void awaitConfirmedAndNotified(Flow flow) {
        await().atMost(LONG_ENOUGH)
                .untilAsserted(
                        () -> {
                            assertThat(registrationStatus(flow.registration()))
                                    .isEqualTo("CONFIRMED");
                            assertThat(
                                            jdbc.queryForObject(
                                                    "SELECT count(*) FROM notifications WHERE"
                                                            + " user_id = ? AND channel = 'EMAIL'"
                                                            + " AND status = 'SENT'",
                                                    Integer.class,
                                                    flow.student().value()))
                                    .isEqualTo(1);
                        });
    }

    // ---------- the tests ----------

    @Test
    @DisplayName(
            "D65: a correlation id the client sends is ignored; the response carries the"
                    + " request's own trace id")
    void aClientSuppliedIdIsIgnored() {
        var headers = new HttpHeaders();
        headers.add("X-Correlation-Id", "abc");

        var response =
                http.exchange(
                        "/api/v1/workshops",
                        HttpMethod.GET,
                        new HttpEntity<>(headers),
                        String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(correlationHeader(response)).isNotEqualTo("abc");
    }

    @Test
    @DisplayName(
            "M-53: one id from the charge request to the outbox row, the message header and"
                    + " the logs of two consumers, across two asynchronous steps")
    void chargeIdTravelsThroughTheOutboxAndBothConsumers() throws Exception {
        var flow = registerAndPay(new ConfirmOutcome.Succeeded());
        awaitConfirmedAndNotified(flow);

        assertThat(flow.chargeId()).isNotEqualTo(flow.registerId());
        // the request wrote the outbox row under its own id (AC66.1)
        assertThat(storedHoldIds(flow.registration())).containsExactly(flow.registerId());
        assertThat(storedIds("unihub.payment.charge.succeeded", flow.registration()))
                .containsExactly(flow.chargeId());
        // step one: the relay put it in the header, the payment-confirmation consumer logged it,
        // and the row that consumer wrote in its transaction carries it on
        assertThat(receivedOn("unihub.payment-confirmation.queue"))
                .containsExactly(flow.chargeId());
        assertThat(
                        jdbc.queryForList(
                                "SELECT correlation_id FROM event_publications WHERE routing_key ="
                                        + " 'unihub.registration.ticket.confirmed' AND aggregate_id"
                                        + " = ?",
                                String.class,
                                flow.registration()))
                .containsExactly(flow.chargeId());
        // step two: the notification consumer, and the log line its use case wrote (AC67.1)
        assertThat(receivedOn("unihub.notification.queue")).containsExactly(flow.chargeId());
        assertThat(loggedBy("DispatchNotifications")).containsExactly(flow.chargeId());
        // the hold job went through the delay queue and back with the registration request's id
        await().atMost(LONG_ENOUGH)
                .untilAsserted(
                        () ->
                                assertThat(receivedOn("unihub.seat-hold-expiry.queue"))
                                        .containsExactly(flow.registerId()));
    }

    @Test
    @DisplayName(
            "M-53: two flows on the same listener threads keep their own ids; nothing leaks"
                    + " from one message to the next")
    void nothingLeaksFromOneMessageToTheNext() throws Exception {
        var first = registerAndPay(new ConfirmOutcome.Succeeded());
        awaitConfirmedAndNotified(first);
        var second = registerAndPay(new ConfirmOutcome.Succeeded());
        awaitConfirmedAndNotified(second);

        assertThat(first.chargeId()).isNotEqualTo(second.chargeId());
        assertThat(receivedOn("unihub.payment-confirmation.queue"))
                .containsExactlyInAnyOrder(first.chargeId(), second.chargeId());
        assertThat(receivedOn("unihub.notification.queue"))
                .containsExactlyInAnyOrder(first.chargeId(), second.chargeId());
        assertThat(loggedBy("DispatchNotifications"))
                .containsExactlyInAnyOrder(first.chargeId(), second.chargeId());
        assertThat(storedIds("unihub.payment.charge.succeeded", first.registration()))
                .containsExactly(first.chargeId());
        assertThat(storedIds("unihub.payment.charge.succeeded", second.registration()))
                .containsExactly(second.chargeId());
    }

    @Test
    @DisplayName(
            "D66: work no request started, a scheduled sweep, gets an id of its own, and the"
                    + " chain it starts runs under it")
    void aJobCycleHasItsOwnId() throws Exception {
        var flow = registerAndPay(new ConfirmOutcome.Processing());
        var gatewayId =
                jdbc.queryForObject(
                        "SELECT gateway_transaction_id FROM payments WHERE id = ?",
                        String.class,
                        flow.payment());
        when(gateway.retrieve(gatewayId)).thenReturn(GatewayPaymentState.SUCCEEDED);

        await().atMost(LONG_ENOUGH)
                .untilAsserted(
                        () ->
                                assertThat(registrationStatus(flow.registration()))
                                        .isEqualTo("CONFIRMED"));

        var requestedBySweep =
                jdbc.queryForList(
                        "SELECT correlation_id FROM event_publications WHERE routing_key ="
                                + " 'unihub.payment.reconciliation.requested' AND payload ->>"
                                + " 'paymentId' = ?",
                        String.class,
                        flow.payment().toString());
        assertThat(requestedBySweep).hasSize(1);
        var jobId = requestedBySweep.getFirst();
        assertThat(jobId).as("the sweep's own trace id").matches(TRACE_ID);
        assertThat(jobId).isNotEqualTo(flow.chargeId()).isNotEqualTo(flow.registerId());
        assertThat(receivedOn("unihub.reconciliation.queue")).contains(jobId);
        // the consumer resolved the payment under the job's id, so the success it announced, and
        // the confirmation that followed, belong to the same chain
        assertThat(storedIds("unihub.payment.charge.succeeded", flow.registration()))
                .containsExactly(jobId);
        assertThat(receivedOn("unihub.payment-confirmation.queue")).contains(jobId);
    }
}
