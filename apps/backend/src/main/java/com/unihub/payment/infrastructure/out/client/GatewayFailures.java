package com.unihub.payment.infrastructure.out.client;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.nio.channels.UnresolvedAddressException;

/**
 * Decides whether a failed request can have reached the gateway. This is the money-safety core of
 * the confirm (payment.md §3, checklist D16): a confirm called "not reached" is cancelled and its
 * idempotency key deleted, so a same-key retry creates a second payment while the first may already
 * have charged the card. The list of causes that prove the request never left is therefore short
 * and explicit; everything else counts as possibly sent. Notably a read timeout and a connect
 * timeout are different types here, which is why the adapter uses {@code java.net.http} and not the
 * SDK's default {@code HttpURLConnection} (both are a {@code SocketTimeoutException} there, told
 * apart only by their message).
 */
final class GatewayFailures {
    private GatewayFailures() {}

    /** True only when some cause in the chain proves no byte of the request was sent. */
    static boolean provablyNotSent(Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException
                    || cause instanceof UnknownHostException
                    || cause instanceof NoRouteToHostException
                    || cause instanceof HttpConnectTimeoutException
                    || cause instanceof UnresolvedAddressException) {
                return true;
            }
            if (cause.getCause() == cause) {
                return false;
            }
        }
        return false;
    }
}
