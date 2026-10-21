package com.unihub.payment.infrastructure.out.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The allow-list that decides "this confirm never left UniHub". A wrong "yes" cancels a payment
 * whose card may have been charged, so the cases that must be "no" matter as much as the "yes"
 * ones.
 */
class GatewayFailuresTest {

    @Test
    @DisplayName(
            "a refused connection, an unknown host and a connect timeout prove nothing was sent")
    void provablyNotSent() {
        assertThat(GatewayFailures.provablyNotSent(new ConnectException("refused"))).isTrue();
        assertThat(GatewayFailures.provablyNotSent(new UnknownHostException("nope"))).isTrue();
        assertThat(GatewayFailures.provablyNotSent(new HttpConnectTimeoutException("connect")))
                .isTrue();
        assertThat(GatewayFailures.provablyNotSent(new UnresolvedAddressException())).isTrue();
    }

    @Test
    @DisplayName("the cause may be wrapped: the chain is searched")
    void wrapped() {
        var wrapped = new RuntimeException("sdk", new IOException("io", new ConnectException()));

        assertThat(GatewayFailures.provablyNotSent(wrapped)).isTrue();
    }

    @Test
    @DisplayName("a read timeout is not a connect timeout, though both are timeouts")
    void readTimeout_mayHaveBeenSent() {
        assertThat(GatewayFailures.provablyNotSent(new HttpTimeoutException("response"))).isFalse();
        assertThat(GatewayFailures.provablyNotSent(new SocketTimeoutException("Read timed out")))
                .isFalse();
    }

    @Test
    @DisplayName("a reset, a TLS failure and anything unknown count as possibly sent")
    void everythingElse_mayHaveBeenSent() {
        assertThat(GatewayFailures.provablyNotSent(new SocketException("Connection reset")))
                .isFalse();
        assertThat(GatewayFailures.provablyNotSent(new SSLHandshakeException("tls"))).isFalse();
        assertThat(GatewayFailures.provablyNotSent(new IOException("closed"))).isFalse();
        assertThat(GatewayFailures.provablyNotSent(new RuntimeException("?"))).isFalse();
        assertThat(GatewayFailures.provablyNotSent(null)).isFalse();
    }
}
