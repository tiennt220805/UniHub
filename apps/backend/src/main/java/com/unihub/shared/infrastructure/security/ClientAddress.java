package com.unihub.shared.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * The address a request's budget is charged to (distributed-coordination.md §3, Phase 7 checklist
 * D91). No header a client can write is trusted.
 *
 * <p>Behind the Istio ingress gateway the container's peer is always its own sidecar, {@code
 * 127.0.0.6}; the gateway sets and overwrites {@code X-Envoy-External-Address} with the address it
 * saw. That header is read <b>only</b> when the peer is the sidecar, and only if it is an IP
 * literal. In every other case (docker-compose, tests, no gateway in front) the socket's address is
 * used. {@code X-Forwarded-For} is never read: it is client-written, and a walk through it would
 * follow whatever the client put there.
 */
final class ClientAddress {

    /** Istio's inbound passthrough address: the peer of every connection a sidecar forwards. */
    static final String SIDECAR_PEER = "127.0.0.6";

    static final String GATEWAY_HEADER = "X-Envoy-External-Address";

    private static final int MAX_LENGTH = 45; // longest IPv6 text form
    private static final Pattern IPV4 =
            Pattern.compile(
                    "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}");
    private static final Pattern IPV6_CHARS = Pattern.compile("[0-9A-Fa-f:.]+");

    private ClientAddress() {}

    static String of(HttpServletRequest request) {
        var peer = request.getRemoteAddr();
        if (SIDECAR_PEER.equals(peer)) {
            var forwarded = literal(request.getHeader(GATEWAY_HEADER));
            if (forwarded != null) {
                return forwarded;
            }
        }
        var socket = literal(peer);
        return socket != null ? socket : String.valueOf(peer);
    }

    /**
     * The normalised text of an IP literal, or {@code null} for anything else. Only a dotted quad
     * or a string with a colon is parsed: that is what keeps {@link InetAddress#getByName} from
     * ever treating the value as a host name and resolving it.
     */
    static String literal(String value) {
        if (value == null) {
            return null;
        }
        var text = value.strip();
        if (text.isEmpty() || text.length() > MAX_LENGTH) {
            return null;
        }
        boolean v4 = IPV4.matcher(text).matches();
        boolean v6 = text.indexOf(':') >= 0 && IPV6_CHARS.matcher(text).matches();
        if (!v4 && !v6) {
            return null;
        }
        try {
            return InetAddress.getByName(text).getHostAddress();
        } catch (UnknownHostException notAnAddress) {
            return null;
        }
    }
}
