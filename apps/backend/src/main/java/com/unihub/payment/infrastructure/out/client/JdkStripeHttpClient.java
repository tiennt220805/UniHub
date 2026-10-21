package com.unihub.payment.infrastructure.out.client;

import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.StripeException;
import com.stripe.net.HttpClient;
import com.stripe.net.StripeRequest;
import com.stripe.net.StripeResponse;
import java.net.URISyntaxException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The SDK's transport over {@code java.net.http}. The SDK's default {@code HttpURLConnection}
 * client reports a connect timeout and a read timeout as the same exception type, and only the
 * message tells them apart; {@link GatewayFailures} must not decide whether money moved by matching
 * text. Here a connect timeout is an {@code HttpConnectTimeoutException}, and a stalled response is
 * a plain timeout.
 *
 * <p>Every failure leaves as the SDK's {@link ApiConnectionException} with the original exception
 * as its cause. The SDK does not retry (max network retries is 0).
 */
final class JdkStripeHttpClient extends HttpClient {
    private final java.net.http.HttpClient http;
    private final Duration readTimeout;

    /**
     * Upper bound for the whole exchange, so a response body that stalls cannot hold the thread.
     */
    private final Duration overall;

    JdkStripeHttpClient(Duration connectTimeout, Duration readTimeout) {
        this.http =
                java.net.http.HttpClient.newBuilder()
                        .connectTimeout(connectTimeout)
                        .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                        .build();
        this.readTimeout = readTimeout;
        this.overall = connectTimeout.plus(readTimeout);
    }

    @Override
    public StripeResponse request(StripeRequest request) throws StripeException {
        var future =
                http.sendAsync(
                        toHttp(request),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        try {
            var response = future.get(overall.toMillis(), TimeUnit.MILLISECONDS);
            return new StripeResponse(
                    response.statusCode(),
                    com.stripe.net.HttpHeaders.of(response.headers().map()),
                    response.body());
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new ApiConnectionException(
                    "The gateway did not answer in time: " + request.url(),
                    new java.net.SocketTimeoutException("response timed out"));
        } catch (ExecutionException e) {
            throw new ApiConnectionException(
                    "I/O error talking to the gateway: " + request.url(), e.getCause());
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ApiConnectionException("Interrupted while talking to the gateway", e);
        }
    }

    private HttpRequest toHttp(StripeRequest request) throws ApiConnectionException {
        try {
            var builder = HttpRequest.newBuilder(request.url().toURI()).timeout(readTimeout);
            request.headers().map().forEach((name, values) -> addAll(builder, name, values));
            var content = request.content();
            if (content != null && content.byteArrayContent() != null) {
                builder.header("Content-Type", content.contentType());
                builder.method(
                        request.method().name(),
                        HttpRequest.BodyPublishers.ofByteArray(content.byteArrayContent()));
            } else {
                builder.method(request.method().name(), HttpRequest.BodyPublishers.noBody());
            }
            return builder.build();
        } catch (URISyntaxException e) {
            throw new ApiConnectionException("Bad gateway URL: " + request.url(), e);
        }
    }

    private static void addAll(HttpRequest.Builder builder, String name, List<String> values) {
        if (name.equalsIgnoreCase("Content-Type") || name.equalsIgnoreCase("Content-Length")) {
            return;
        }
        values.forEach(value -> builder.header(name, value));
    }
}
