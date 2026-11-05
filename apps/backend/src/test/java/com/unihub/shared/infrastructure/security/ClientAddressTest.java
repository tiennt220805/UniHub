package com.unihub.shared.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Which address a budget is charged to (Phase 7 checklist D91, S-16): only the value the edge
 * gateway sets, and only when the peer is the pod's own sidecar; never a header the client wrote.
 */
class ClientAddressTest {

    private static MockHttpServletRequest from(String peer) {
        var request = new MockHttpServletRequest("GET", "/api/v1/workshops");
        request.setRemoteAddr(peer);
        return request;
    }

    @Test
    @DisplayName("behind the sidecar, the address the gateway set is the client's")
    void sidecarPeer_usesGatewayHeader() {
        var request = from("127.0.0.6");
        request.addHeader("X-Envoy-External-Address", "203.0.113.7");

        assertThat(ClientAddress.of(request)).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("any other peer: the header is ignored, the socket address is used")
    void otherPeer_ignoresGatewayHeader() {
        var request = from("198.51.100.20");
        request.addHeader("X-Envoy-External-Address", "203.0.113.7");

        assertThat(ClientAddress.of(request)).isEqualTo("198.51.100.20");
    }

    @Test
    @DisplayName("X-Forwarded-For is never read, behind the sidecar or not")
    void forwardedFor_isNeverRead() {
        var behind = from("127.0.0.6");
        behind.addHeader("X-Forwarded-For", "203.0.113.99");
        behind.addHeader("X-Envoy-External-Address", "203.0.113.7");
        var direct = from("198.51.100.20");
        direct.addHeader("X-Forwarded-For", "203.0.113.99");

        assertThat(ClientAddress.of(behind)).isEqualTo("203.0.113.7");
        assertThat(ClientAddress.of(direct)).isEqualTo("198.51.100.20");
    }

    @Test
    @DisplayName("behind the sidecar with no gateway header: the sidecar's own address")
    void sidecarPeer_withoutHeader_fallsBackToSocket() {
        assertThat(ClientAddress.of(from("127.0.0.6"))).isEqualTo("127.0.0.6");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "localhost",
                "example.com",
                "abc", // hex characters only: a host name to the resolver
                "999.1.1.1",
                "1.2.3",
                "1.2.3.4.5",
                "203.0.113.7, 10.0.0.1",
                "203.0.113.7\r\nX-Injected: 1",
                "fe80::1%eth0", // a scoped address is not accepted
                "::1::2",
                "",
                "   ",
            })
    @DisplayName("a gateway header that is not an IP literal is ignored, and never resolved")
    void notAnIpLiteral_isIgnored(String value) {
        var request = from("127.0.0.6");
        if (!value.isBlank()) {
            // MockHttpServletRequest accepts any value; a real container would refuse CRLF
            request.addHeader("X-Envoy-External-Address", value.replace("\r\n", " "));
        }

        assertThat(ClientAddress.of(request)).isEqualTo("127.0.0.6");
    }

    @Test
    @DisplayName("an over-long value is ignored")
    void tooLong_isIgnored() {
        var request = from("127.0.0.6");
        request.addHeader("X-Envoy-External-Address", "1".repeat(300));

        assertThat(ClientAddress.of(request)).isEqualTo("127.0.0.6");
    }

    @Test
    @DisplayName("IPv6 is normalised, so two spellings of one address share a budget")
    void ipv6_isNormalised() {
        var short1 = from("127.0.0.6");
        short1.addHeader("X-Envoy-External-Address", "2001:db8::1");
        var long1 = from("127.0.0.6");
        long1.addHeader("X-Envoy-External-Address", "2001:0DB8:0:0:0:0:0:1");

        assertThat(ClientAddress.of(short1)).isEqualTo(ClientAddress.of(long1));
    }
}
