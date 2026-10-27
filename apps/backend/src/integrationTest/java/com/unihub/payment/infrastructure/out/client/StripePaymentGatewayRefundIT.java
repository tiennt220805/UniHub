package com.unihub.payment.infrastructure.out.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.notContaining;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import com.unihub.payment.application.port.out.GatewayCallFailedException;
import com.unihub.payment.application.port.out.GatewayNotReachedException;
import com.unihub.payment.application.port.out.GatewayRefundState;
import com.unihub.payment.application.port.out.PaymentGateway;
import com.unihub.shared.domain.id.PaymentId;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
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
 * M-76, INV-18 (payment.md §9, checklist D25): the refund half of the Stripe adapter against a
 * WireMock stand-in. What it lists, what it creates and under which key, how each {@code
 * Refund.status} maps, and how each failure is classified (the class decides whether a refund waits
 * in its retry chain forever or follows the three-attempt rule). Plain JUnit: no Spring context (it
 * extends the support class only because every IT runs on the shared containers).
 */
class StripePaymentGatewayRefundIT extends IntegrationTestSupport {
    private static final String LIST = "/v1/refunds";
    private static final String CREATE = "/v1/refunds";
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

    private static String refundJson(String id, String status) {
        return "{\"id\":\""
                + id
                + "\",\"object\":\"refund\",\"payment_intent\":\"pi_test_1\",\"status\":"
                + (status == null ? "null" : "\"" + status + "\"")
                + "}";
    }

    private static String list(String... refunds) {
        return "{\"object\":\"list\",\"url\":\"/v1/refunds\",\"has_more\":false,\"data\":["
                + String.join(",", refunds)
                + "]}";
    }

    private void stubList(String body) {
        gateway.stubFor(
                get(urlPathEqualTo(LIST))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(body)));
    }

    private void stubCreate(String body) {
        gateway.stubFor(
                post(urlEqualTo(CREATE))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(body)));
    }

    // ---------- findRefund: a plain read ----------

    @Test
    @DisplayName("findRefund lists the refunds of the PaymentIntent, and nothing else")
    void findRefund_listsByPaymentIntent() {
        stubList(list(refundJson("re_1", "succeeded")));

        var found = adapter().findRefund("pi_test_1");

        assertThat(found).contains(GatewayRefundState.SUCCEEDED);
        gateway.verify(
                getRequestedFor(urlPathEqualTo(LIST))
                        .withQueryParam("payment_intent", equalTo("pi_test_1"))
                        .withHeader("Authorization", equalTo("Bearer dummy-secret-for-tests")));
        gateway.verify(0, postRequestedFor(urlPathEqualTo(CREATE)));
    }

    @Test
    @DisplayName("no refund on the gateway is an empty answer, not an error")
    void findRefund_none_isEmpty() {
        stubList(list());

        assertThat(adapter().findRefund("pi_test_1")).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "succeeded,SUCCEEDED",
        "pending,PENDING",
        "requires_action,PENDING",
        "failed,FAILED",
        "canceled,FAILED"
    })
    @DisplayName("the five values of Refund.status map to three states (payment.md §9)")
    void statusMapping(String gatewayStatus, GatewayRefundState expected) {
        stubList(list(refundJson("re_1", gatewayStatus)));
        stubCreate(refundJson("re_1", gatewayStatus));

        assertThat(adapter().findRefund("pi_test_1")).contains(expected);
        assertThat(adapter().refund(payment, "pi_test_1")).isEqualTo(expected);
    }

    @Test
    @DisplayName("with several refunds the best state wins: a succeeded one beats a failed one")
    void findRefund_severalRefunds_bestStateWins() {
        stubList(
                list(
                        refundJson("re_1", "failed"),
                        refundJson("re_2", "pending"),
                        refundJson("re_3", "succeeded")));
        assertThat(adapter().findRefund("pi_test_1")).contains(GatewayRefundState.SUCCEEDED);

        stubList(list(refundJson("re_1", "failed"), refundJson("re_2", "pending")));
        assertThat(adapter().findRefund("pi_test_1")).contains(GatewayRefundState.PENDING);
    }

    @Test
    @DisplayName("a refund status nobody expects, or none, is not guessed at: a failed call")
    void unknownStatus_isCallFailed() {
        stubList(list(refundJson("re_1", "teleported")));
        assertThatThrownBy(() -> adapter().findRefund("pi_test_1"))
                .isInstanceOf(GatewayCallFailedException.class);

        stubList(list(refundJson("re_1", null)));
        assertThatThrownBy(() -> adapter().findRefund("pi_test_1"))
                .isInstanceOf(GatewayCallFailedException.class);

        stubCreate(refundJson("re_1", "teleported"));
        assertThatThrownBy(() -> adapter().refund(payment, "pi_test_1"))
                .isInstanceOf(GatewayCallFailedException.class);
    }

    // ---------- refund: the one call that moves money back ----------

    @Test
    @DisplayName(
            "refund creates a full refund of the PaymentIntent under the fixed key refund:{id}")
    void refund_sendsAFullRefundUnderTheFixedKey() {
        stubCreate(refundJson("re_1", "succeeded"));

        var state = adapter().refund(payment, "pi_test_1");

        assertThat(state).isEqualTo(GatewayRefundState.SUCCEEDED);
        gateway.verify(
                postRequestedFor(urlEqualTo(CREATE))
                        .withHeader("Idempotency-Key", equalTo("refund:" + payment.value()))
                        .withHeader("Authorization", equalTo("Bearer dummy-secret-for-tests"))
                        .withRequestBody(matching(".*payment_intent=pi_test_1.*"))
                        // no amount: the whole charge is refunded, never a part of it
                        .withRequestBody(notContaining("amount=")));
    }

    @Test
    @DisplayName("asking again sends the very same key, so a retry is the same request to Stripe")
    void refund_sameKeyOnEveryCall() {
        stubCreate(refundJson("re_1", "pending"));

        adapter().refund(payment, "pi_test_1");
        adapter().refund(payment, "pi_test_1");

        gateway.verify(
                2,
                postRequestedFor(urlEqualTo(CREATE))
                        .withHeader("Idempotency-Key", equalTo("refund:" + payment.value())));
    }

    // ---------- failures: how each is classified ----------

    @Test
    @DisplayName("a refused connection never left UniHub: not reached, for both calls")
    void connectionRefused_isNotReached() throws Exception {
        int closedPort;
        try (var socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        var offline = adapterFor("http://localhost:" + closedPort);

        assertThatThrownBy(() -> offline.findRefund("pi_test_1"))
                .isInstanceOf(GatewayNotReachedException.class);
        assertThatThrownBy(() -> offline.refund(payment, "pi_test_1"))
                .isInstanceOf(GatewayNotReachedException.class);
    }

    @Test
    @DisplayName("an error answer, a reset connection or an empty reply is an ordinary failed call")
    void errorAnswers_areCallFailed() {
        gateway.stubFor(get(urlPathEqualTo(LIST)).willReturn(aResponse().withStatus(500)));
        gateway.stubFor(post(urlEqualTo(CREATE)).willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> adapter().findRefund("pi_test_1"))
                .isExactlyInstanceOf(GatewayCallFailedException.class);
        assertThatThrownBy(() -> adapter().refund(payment, "pi_test_1"))
                .isExactlyInstanceOf(GatewayCallFailedException.class);

        gateway.stubFor(
                post(urlEqualTo(CREATE))
                        .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        assertThatThrownBy(() -> adapter().refund(payment, "pi_test_1"))
                .as("it may have been sent: never 'not reached'")
                .isExactlyInstanceOf(GatewayCallFailedException.class);
    }

    @Test
    @DisplayName("a read timeout ends the call at the configured limit, as a failed call")
    void readTimeout_isCallFailed() {
        gateway.stubFor(
                post(urlEqualTo(CREATE))
                        .willReturn(aResponse().withStatus(200).withFixedDelay(5_000)));
        var started = System.nanoTime();

        assertThatThrownBy(() -> adapter().refund(payment, "pi_test_1"))
                .isExactlyInstanceOf(GatewayCallFailedException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
    }
}
