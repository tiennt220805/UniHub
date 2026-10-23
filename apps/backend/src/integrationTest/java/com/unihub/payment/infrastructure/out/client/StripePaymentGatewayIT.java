package com.unihub.payment.infrastructure.out.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import com.unihub.payment.application.port.out.ConfirmOutcome;
import com.unihub.payment.application.port.out.GatewayCallFailedException;
import com.unihub.payment.application.port.out.GatewayCancelRefusedException;
import com.unihub.payment.application.port.out.GatewayNotReachedException;
import com.unihub.payment.application.port.out.GatewayOutcomeUnknownException;
import com.unihub.payment.application.port.out.GatewayPaymentState;
import com.unihub.payment.application.port.out.PaymentGateway;
import com.unihub.payment.application.port.out.PaymentMethodRejectedException;
import com.unihub.shared.domain.id.PaymentId;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * M-13 (INV-18, payment.md §3, checklist D16): the Stripe adapter against a WireMock stand-in for
 * the gateway (mocking-guidelines.md §2). It proves what each call sends and, above all, how each
 * failure is classified: "never left UniHub" lets the orchestrator cancel the payment and delete
 * the key, so it must be said only when it is certain. Plain JUnit: the adapter needs no Spring
 * context.
 */
class StripePaymentGatewayIT extends IntegrationTestSupport {
    private static final String CREATE = "/v1/payment_intents";
    private static final String CONFIRM = "/v1/payment_intents/pi_test_1/confirm";
    private static final String RETRIEVE = "/v1/payment_intents/pi_test_1";
    private static final String CANCEL = "/v1/payment_intents/pi_test_1/cancel";
    private static final Duration READ_TIMEOUT = Duration.ofMillis(400);

    private static WireMockServer gateway;

    private final PaymentId payment = PaymentId.newId();

    @BeforeAll
    static void startGateway() {
        gateway = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        gateway.start();
    }

    @AfterAll
    static void stopGateway() {
        gateway.stop();
    }

    @BeforeEach
    void resetGateway() {
        gateway.resetAll();
    }

    private static PaymentGateway adapterFor(String apiBase) {
        return StripeGatewayFactory.create(
                new StripeProperties(
                        "dummy-secret-for-tests", apiBase, Duration.ofMillis(400), READ_TIMEOUT));
    }

    private PaymentGateway adapter() {
        return adapterFor("http://localhost:" + gateway.port());
    }

    private static String json(String id, String status) {
        return "{\"id\":\""
                + id
                + "\",\"object\":\"payment_intent\",\"status\":\""
                + status
                + "\"}";
    }

    private void stubCreate() {
        gateway.stubFor(
                post(urlEqualTo(CREATE))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(json("pi_test_1", "requires_confirmation"))));
    }

    private void stubConfirm(int status, String body) {
        gateway.stubFor(
                post(urlEqualTo(CONFIRM))
                        .willReturn(
                                aResponse()
                                        .withStatus(status)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(body)));
    }

    // ---------- create: moves no money ----------

    @Test
    @DisplayName(
            "create sends an unconfirmed intent in the right unit, with a fixed key and metadata")
    void create_sendsAnUnconfirmedIntent() {
        stubCreate();

        var id = adapter().create(payment, new BigDecimal("150000"), "VND", "pm_card_visa");

        assertThat(id).isEqualTo("pi_test_1");
        gateway.verify(
                postRequestedFor(urlEqualTo(CREATE))
                        .withHeader("Idempotency-Key", equalTo("pi-create:" + payment.value()))
                        .withHeader("Authorization", equalTo("Bearer dummy-secret-for-tests"))
                        .withRequestBody(containing("confirm=false"))
                        .withRequestBody(containing("amount=150000"))
                        .withRequestBody(containing("currency=vnd"))
                        .withRequestBody(containing("payment_method=pm_card_visa"))
                        .withRequestBody(containing("metadata[payment_id]=" + payment.value()))
                        .withRequestBody(
                                containing("automatic_payment_methods[allow_redirects]=never")));
    }

    @Test
    @DisplayName("a two-decimal currency is sent in cents")
    void create_usesCentsForUsd() {
        stubCreate();

        adapter().create(payment, new BigDecimal("12.34"), "USD", "pm_card_visa");

        gateway.verify(
                postRequestedFor(urlEqualTo(CREATE))
                        .withRequestBody(containing("amount=1234"))
                        .withRequestBody(containing("currency=usd")));
    }

    @Test
    @DisplayName("any failure of create, a read timeout included, is 'nothing was confirmed'")
    void create_anyFailure_isCallFailed() {
        gateway.stubFor(
                post(urlEqualTo(CREATE))
                        .willReturn(aResponse().withStatus(200).withFixedDelay(5_000)));
        var started = System.nanoTime();

        assertThatThrownBy(() -> adapter().create(payment, new BigDecimal("1000"), "VND", "pm_x"))
                .isInstanceOf(GatewayCallFailedException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .as("the read timeout, not the gateway's delay, ended the call")
                .isLessThan(Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("create answered with an error, or with a reset connection, is also call-failed")
    void create_errorAnswers_areCallFailed() {
        gateway.stubFor(post(urlEqualTo(CREATE)).willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> adapter().create(payment, new BigDecimal("1000"), "VND", "pm_x"))
                .isInstanceOf(GatewayCallFailedException.class);

        gateway.stubFor(
                post(urlEqualTo(CREATE))
                        .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        assertThatThrownBy(() -> adapter().create(payment, new BigDecimal("1000"), "VND", "pm_x"))
                .isInstanceOf(GatewayCallFailedException.class);
    }

    @Test
    @DisplayName("a refused payment method token is its own outcome, not a gateway failure")
    void create_rejectedPaymentMethod() {
        gateway.stubFor(
                post(urlEqualTo(CREATE))
                        .willReturn(
                                aResponse()
                                        .withStatus(400)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                "{\"error\":{\"type\":\"invalid_request_error\","
                                                        + "\"code\":\"resource_missing\","
                                                        + "\"param\":\"payment_method\","
                                                        + "\"message\":\"No such PaymentMethod\"}}")));

        assertThatThrownBy(
                        () -> adapter().create(payment, new BigDecimal("1000"), "VND", "pm_nope"))
                .isInstanceOf(PaymentMethodRejectedException.class);
    }

    @Test
    @DisplayName(
            "an invalid request about anything else is still a failed call, not the caller's fault")
    void create_otherInvalidRequest_isCallFailed() {
        gateway.stubFor(
                post(urlEqualTo(CREATE))
                        .willReturn(
                                aResponse()
                                        .withStatus(400)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                "{\"error\":{\"type\":\"invalid_request_error\","
                                                        + "\"param\":\"amount\","
                                                        + "\"message\":\"Invalid amount\"}}")));

        assertThatThrownBy(() -> adapter().create(payment, new BigDecimal("1000"), "VND", "pm_x"))
                .isInstanceOf(GatewayCallFailedException.class);
    }

    // ---------- confirm: the call that moves money ----------

    @Test
    @DisplayName(
            "confirm sends its own fixed key and refuses a payment that needs the customer's action")
    void confirm_sendsItsKeyAndErrorOnRequiresAction() {
        stubConfirm(200, json("pi_test_1", "succeeded"));

        var outcome = adapter().confirm(payment, "pi_test_1");

        assertThat(outcome).isInstanceOf(ConfirmOutcome.Succeeded.class);
        gateway.verify(
                postRequestedFor(urlEqualTo(CONFIRM))
                        .withHeader("Idempotency-Key", equalTo("pi-confirm:" + payment.value()))
                        .withRequestBody(containing("error_on_requires_action=true")));
    }

    @Test
    @DisplayName("a retry of the confirm carries the same key, so the gateway sees one request")
    void confirm_retry_reusesTheKey() {
        stubConfirm(200, json("pi_test_1", "succeeded"));

        adapter().confirm(payment, "pi_test_1");
        adapter().confirm(payment, "pi_test_1");

        gateway.verify(
                2,
                postRequestedFor(urlEqualTo(CONFIRM))
                        .withHeader("Idempotency-Key", equalTo("pi-confirm:" + payment.value())));
    }

    @Test
    @DisplayName("processing is an answer: the outcome comes later")
    void confirm_processing() {
        stubConfirm(200, json("pi_test_1", "processing"));

        assertThat(adapter().confirm(payment, "pi_test_1"))
                .isInstanceOf(ConfirmOutcome.Processing.class);
    }

    @Test
    @DisplayName("a declined card (402 card_error) is a Declined outcome with its decline code")
    void confirm_declined() {
        stubConfirm(
                402,
                "{\"error\":{\"type\":\"card_error\",\"code\":\"card_declined\","
                        + "\"decline_code\":\"generic_decline\",\"message\":\"Your card was declined.\"}}");

        var outcome = adapter().confirm(payment, "pi_test_1");

        assertThat(outcome).isEqualTo(new ConfirmOutcome.Declined("generic_decline"));
    }

    @Test
    @DisplayName("an intent that comes back requiring a new payment method is a decline")
    void confirm_requiresPaymentMethod_isDeclined() {
        stubConfirm(200, json("pi_test_1", "requires_payment_method"));

        assertThat(adapter().confirm(payment, "pi_test_1"))
                .isInstanceOf(ConfirmOutcome.Declined.class);
    }

    @Test
    @DisplayName("a status nobody expects after a confirm is left for reconciliation, not guessed")
    void confirm_unexpectedStatus_isUnknown() {
        stubConfirm(200, json("pi_test_1", "requires_action"));

        assertThatThrownBy(() -> adapter().confirm(payment, "pi_test_1"))
                .isInstanceOf(GatewayOutcomeUnknownException.class);
    }

    @Test
    @DisplayName("a 5xx, a rejected request and a rate limit reached the gateway: outcome unknown")
    void confirm_errorAnswers_areUnknown() {
        for (var status : new int[] {500, 503, 400, 401, 409, 429}) {
            stubConfirm(status, "{\"error\":{\"type\":\"api_error\",\"message\":\"x\"}}");

            assertThatThrownBy(() -> adapter().confirm(payment, "pi_test_1"))
                    .as("HTTP %d", status)
                    .isInstanceOf(GatewayOutcomeUnknownException.class);
        }
    }

    @Test
    @DisplayName(
            "a response that outlasts the read timeout is unknown, and the call returns in time")
    void confirm_readTimeout_isUnknown_andBounded() {
        gateway.stubFor(
                post(urlEqualTo(CONFIRM))
                        .willReturn(aResponse().withStatus(200).withFixedDelay(5_000)));
        var started = System.nanoTime();

        assertThatThrownBy(() -> adapter().confirm(payment, "pi_test_1"))
                .isInstanceOf(GatewayOutcomeUnknownException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .as("the 400ms read timeout ended the call, not the 5s delay")
                .isLessThan(Duration.ofSeconds(3));
    }

    @Test
    @DisplayName(
            "a connection reset or an empty reply after sending is unknown, never 'not reached'")
    void confirm_resetAfterSending_isUnknown() {
        for (var fault :
                new Fault[] {
                    Fault.CONNECTION_RESET_BY_PEER,
                    Fault.EMPTY_RESPONSE,
                    Fault.MALFORMED_RESPONSE_CHUNK
                }) {
            gateway.stubFor(post(urlEqualTo(CONFIRM)).willReturn(aResponse().withFault(fault)));

            assertThatThrownBy(() -> adapter().confirm(payment, "pi_test_1"))
                    .as(fault.name())
                    .isInstanceOf(GatewayOutcomeUnknownException.class);
        }
    }

    @Test
    @DisplayName("a refused connection never left UniHub: not reached")
    void confirm_connectionRefused_isNotReached() throws Exception {
        int closedPort;
        try (var socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        assertThatThrownBy(
                        () ->
                                adapterFor("http://localhost:" + closedPort)
                                        .confirm(payment, "pi_test_1"))
                .isInstanceOf(GatewayNotReachedException.class);
    }

    @Test
    @DisplayName("a host that does not resolve never left UniHub: not reached")
    void confirm_unknownHost_isNotReached() {
        assertThatThrownBy(
                        () ->
                                adapterFor("http://no-such-host.invalid")
                                        .confirm(payment, "pi_test_1"))
                .isInstanceOf(GatewayNotReachedException.class);
    }

    // ---------- retrieve and cancel: reconciliation (payment.md §6, AC16.4, AC16.5) ----------

    private void stubRetrieve(String body) {
        gateway.stubFor(
                get(urlEqualTo(RETRIEVE))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(body)));
    }

    private static String intent(String status, String latestCharge) {
        return "{\"id\":\"pi_test_1\",\"object\":\"payment_intent\",\"status\":\""
                + status
                + "\",\"latest_charge\":"
                + (latestCharge == null ? "null" : "\"" + latestCharge + "\"")
                + "}";
    }

    @ParameterizedTest(name = "{0} (latest_charge {1}) is {2}")
    @CsvSource(
            nullValues = "null",
            value = {
                "succeeded, ch_1, SUCCEEDED",
                "requires_payment_method, ch_1, DECLINED",
                "requires_payment_method, null, NEVER_CONFIRMED",
                "requires_confirmation, null, NEVER_CONFIRMED",
                "requires_action, null, CANCEL_REQUIRED",
                "requires_capture, ch_1, CANCEL_REQUIRED",
                "processing, null, PROCESSING",
                "canceled, null, CANCELED"
            })
    @DisplayName("retrieve maps every PaymentIntent status of payment.md §6 to one state")
    void retrieve_mapsEveryStatus(String status, String latestCharge, GatewayPaymentState state) {
        stubRetrieve(intent(status, latestCharge));

        assertThat(adapter().retrieve("pi_test_1")).isEqualTo(state);

        gateway.verify(
                getRequestedFor(urlEqualTo(RETRIEVE))
                        .withHeader("Authorization", equalTo("Bearer dummy-secret-for-tests")));
    }

    @Test
    @DisplayName("a status nobody knows is not guessed at: the call fails, to be asked again")
    void retrieve_unknownStatus_isCallFailed() {
        stubRetrieve(intent("some_future_status", null));

        assertThatThrownBy(() -> adapter().retrieve("pi_test_1"))
                .isInstanceOf(GatewayCallFailedException.class);
    }

    @Test
    @DisplayName("an answer without a status is a failed call too")
    void retrieve_noStatus_isCallFailed() {
        stubRetrieve("{\"id\":\"pi_test_1\",\"object\":\"payment_intent\"}");

        assertThatThrownBy(() -> adapter().retrieve("pi_test_1"))
                .isInstanceOf(GatewayCallFailedException.class);
    }

    @Test
    @DisplayName("an error answer, a missing payment and a read timeout fail the read")
    void retrieve_errors_areCallFailed() {
        for (var failure :
                new com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder[] {
                    aResponse().withStatus(500).withBody("{\"error\":{\"message\":\"boom\"}}"),
                    aResponse()
                            .withStatus(404)
                            .withBody(
                                    "{\"error\":{\"type\":\"invalid_request_error\","
                                            + "\"code\":\"resource_missing\"}}"),
                    aResponse().withStatus(200).withFixedDelay((int) READ_TIMEOUT.toMillis() * 3),
                    aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)
                }) {
            gateway.resetAll();
            gateway.stubFor(get(urlEqualTo(RETRIEVE)).willReturn(failure));

            assertThatThrownBy(() -> adapter().retrieve("pi_test_1"))
                    .isInstanceOf(GatewayCallFailedException.class);
        }
    }

    @Test
    @DisplayName("cancel sends the fixed per-payment key, so every retry is the same request")
    void cancel_sendsItsKey() {
        gateway.stubFor(
                post(urlEqualTo(CANCEL))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(json("pi_test_1", "canceled"))));

        adapter().cancel(payment, "pi_test_1");
        adapter().cancel(payment, "pi_test_1");

        gateway.verify(
                2,
                postRequestedFor(urlEqualTo(CANCEL))
                        .withHeader("Idempotency-Key", equalTo("pi-cancel:" + payment.value())));
    }

    @Test
    @DisplayName("a cancel refused because the intent is no longer cancellable is its own outcome")
    void cancel_unexpectedState_isRefused() {
        gateway.stubFor(
                post(urlEqualTo(CANCEL))
                        .willReturn(
                                aResponse()
                                        .withStatus(400)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                "{\"error\":{\"type\":\"invalid_request_error\","
                                                        + "\"code\":\"payment_intent_unexpected_state\","
                                                        + "\"message\":\"anything at all\"}}")));

        assertThatThrownBy(() -> adapter().cancel(payment, "pi_test_1"))
                .isInstanceOf(GatewayCancelRefusedException.class);
    }

    @Test
    @DisplayName("any other failure of the cancel is a failed call, not a refusal")
    void cancel_otherFailures_areCallFailed() {
        for (var failure :
                new com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder[] {
                    aResponse().withStatus(500).withBody("{\"error\":{\"message\":\"boom\"}}"),
                    aResponse()
                            .withStatus(400)
                            .withBody(
                                    "{\"error\":{\"type\":\"invalid_request_error\","
                                            + "\"code\":\"resource_missing\"}}"),
                    aResponse().withStatus(429).withBody("{\"error\":{\"message\":\"slow\"}}"),
                    aResponse().withFault(Fault.EMPTY_RESPONSE)
                }) {
            gateway.resetAll();
            gateway.stubFor(post(urlEqualTo(CANCEL)).willReturn(failure));

            assertThatThrownBy(() -> adapter().cancel(payment, "pi_test_1"))
                    .isInstanceOf(GatewayCallFailedException.class);
        }
    }
}
