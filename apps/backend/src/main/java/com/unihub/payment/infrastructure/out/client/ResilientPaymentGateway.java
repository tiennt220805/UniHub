package com.unihub.payment.infrastructure.out.client;

import com.unihub.payment.application.port.out.ConfirmOutcome;
import com.unihub.payment.application.port.out.GatewayCallFailedException;
import com.unihub.payment.application.port.out.GatewayCircuitOpenException;
import com.unihub.payment.application.port.out.GatewayNotReachedException;
import com.unihub.payment.application.port.out.GatewayOutcomeUnknownException;
import com.unihub.payment.application.port.out.GatewayPaymentState;
import com.unihub.payment.application.port.out.PaymentGateway;
import com.unihub.shared.domain.id.PaymentId;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import java.math.BigDecimal;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * The shared {@code paymentGateway} circuit breaker around every gateway call, with the per-call-
 * site time limit and retry (payment.md §2, §3, §5). The only class that knows Resilience4j.
 *
 * <p>Composition, outside in: circuit breaker, retry, time limiter, then the call. The breaker sees
 * the final outcome of a logical call after its own retries and timeout, so one retried call is one
 * data point; and {@code create} and {@code confirm} are two (checklist D17).
 *
 * <ul>
 *   <li>{@code create}, {@code confirm} (the charge, fast): breaker and the sync time limit, no
 *       retry — a charge is never retried blindly.
 *   <li>{@code retrieve} (reconciliation, off the critical path): breaker, retry, async time limit.
 *       A pure read, so retrying cannot charge twice.
 *   <li>{@code cancel}: breaker and the async time limit, no retry here. The gateway key of a
 *       cancel is fixed per payment, so a retried 5xx would only be the gateway replaying its saved
 *       answer; the broker's retry chain covers a lost connection.
 * </ul>
 *
 * <p>What a refusal or a timeout means depends on the call, and is where money safety lives. A
 * confirm that timed out <em>may have been sent</em>: {@link GatewayOutcomeUnknownException}, never
 * "not reached". A confirm the breaker refused was never sent: {@link GatewayNotReachedException}.
 * The circuit counts as failures only what says the gateway is unwell: the three gateway exceptions
 * and timeouts. A refused payment method token and a refused cancel are answers.
 */
public final class ResilientPaymentGateway implements PaymentGateway, AutoCloseable {

    private final PaymentGateway delegate;
    private final CircuitBreaker breaker;
    private final TimeLimiter syncLimit;
    private final TimeLimiter asyncLimit;
    private final Retry asyncRetry;
    private final ExecutorService executor;

    public ResilientPaymentGateway(
            PaymentGateway delegate, CircuitBreaker breaker, PaymentBreakerProperties settings) {
        this.delegate = delegate;
        this.breaker = breaker;
        this.syncLimit = limiter(settings.syncTimeLimit());
        this.asyncLimit = limiter(settings.asyncTimeLimit());
        this.asyncRetry =
                Retry.of(
                        "paymentGatewayStatus",
                        RetryConfig.custom()
                                .maxAttempts(settings.asyncRetryAttempts())
                                .intervalFunction(
                                        IntervalFunction.ofExponentialBackoff(
                                                settings.asyncRetryBackoff(), 2.0))
                                .retryExceptions(
                                        GatewayCallFailedException.class, TimeoutException.class)
                                .build());
        // One virtual thread per call: the call blocks on I/O, and the time limiter needs it off
        // the caller's thread to be able to stop waiting for it.
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    private static TimeLimiter limiter(java.time.Duration limit) {
        return TimeLimiter.of(
                TimeLimiterConfig.custom()
                        .timeoutDuration(limit)
                        .cancelRunningFuture(true)
                        .build());
    }

    /** The breaker configuration the whole payment module shares (payment.md §2). */
    public static io.github.resilience4j.circuitbreaker.CircuitBreakerConfig breakerConfig(
            PaymentBreakerProperties settings) {
        return io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                .slidingWindowType(
                        io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType
                                .COUNT_BASED)
                .slidingWindowSize(settings.windowSize())
                .failureRateThreshold(settings.failureRateThreshold())
                .waitDurationInOpenState(settings.waitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(settings.permittedCallsInHalfOpenState())
                .minimumNumberOfCalls(settings.minimumNumberOfCalls())
                // What says the gateway is unwell. Every other answer counts as a success.
                .recordExceptions(
                        GatewayCallFailedException.class,
                        GatewayNotReachedException.class,
                        GatewayOutcomeUnknownException.class,
                        TimeoutException.class)
                // Answers, not failures of the gateway: they must not move the circuit, and a
                // half-open permit they used is given back.
                .ignoreExceptions(
                        com.unihub.payment.application.port.out.PaymentMethodRejectedException
                                .class,
                        com.unihub.payment.application.port.out.GatewayCancelRefusedException.class)
                .build();
    }

    @Override
    public String create(
            PaymentId paymentId, BigDecimal amount, String currency, String paymentMethodToken) {
        return call(
                syncLimit,
                false,
                () -> delegate.create(paymentId, amount, currency, paymentMethodToken),
                refused -> new GatewayCallFailedException("the payment circuit is open", refused),
                timedOut ->
                        new GatewayCallFailedException(
                                "creating the payment took too long", timedOut));
    }

    @Override
    public ConfirmOutcome confirm(PaymentId paymentId, String gatewayTransactionId) {
        return call(
                syncLimit,
                false,
                () -> delegate.confirm(paymentId, gatewayTransactionId),
                // Refused: nothing was sent. Timed out: it may have been.
                refused -> new GatewayNotReachedException("the payment circuit is open", refused),
                timedOut ->
                        new GatewayOutcomeUnknownException(
                                "the confirm took too long and may have been sent", timedOut));
    }

    @Override
    public GatewayPaymentState retrieve(String gatewayTransactionId) {
        return call(
                asyncLimit,
                true,
                () -> delegate.retrieve(gatewayTransactionId),
                ResilientPaymentGateway::circuitOpen,
                timedOut ->
                        new GatewayCallFailedException(
                                "reading the payment took too long", timedOut));
    }

    @Override
    public void cancel(PaymentId paymentId, String gatewayTransactionId) {
        call(
                asyncLimit,
                false,
                () -> {
                    delegate.cancel(paymentId, gatewayTransactionId);
                    return null;
                },
                ResilientPaymentGateway::circuitOpen,
                timedOut ->
                        new GatewayCallFailedException(
                                "cancelling the payment took too long", timedOut));
    }

    private static RuntimeException circuitOpen(Throwable refused) {
        return new GatewayCircuitOpenException("the payment circuit is open", refused);
    }

    private <T> T call(
            TimeLimiter limit,
            boolean retried,
            Callable<T> operation,
            Function<Throwable, RuntimeException> whenRefused,
            Function<Throwable, RuntimeException> whenTimedOut) {
        Callable<T> timed = limit.decorateFutureSupplier(() -> executor.submit(operation));
        Callable<T> attempted = retried ? Retry.decorateCallable(asyncRetry, timed) : timed;
        try {
            return CircuitBreaker.decorateCallable(breaker, attempted).call();
        } catch (CallNotPermittedException refused) {
            throw whenRefused.apply(refused);
        } catch (TimeoutException timedOut) {
            throw whenTimedOut.apply(timedOut);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw whenTimedOut.apply(interrupted);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("unexpected checked exception from the gateway", e);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
