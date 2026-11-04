package com.unihub.shared.infrastructure.out.observation;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.function.Function;

/**
 * A span and a timer for a call to a third party that Spring does not instrument itself (Stripe,
 * the AI service and the object store go through {@code java.net.http} and the AWS SDK, not through
 * {@code RestTemplate} or {@code WebClient}; observability.md §2, Phase 6 D73).
 *
 * <p>The observation carries two low-cardinality values and nothing else: the {@code operation} (a
 * name the caller chooses from a short fixed list, never a URL) and the {@code outcome}. The
 * exception is deliberately <em>not</em> given to the observation: that would put its message on
 * the span as an event, and these messages carry URLs and gateway detail (the guardrail of §2).
 */
public final class OutboundCall {

    public static final String OPERATION = "operation";
    public static final String OUTCOME = "outcome";
    public static final String SUCCESS = "success";
    public static final String FAILURE = "failure";
    public static final String TIMEOUT = "timeout";

    private OutboundCall() {}

    /** The call, which may throw a checked exception of its own. */
    @FunctionalInterface
    public interface Call<T, E extends Exception> {
        T run() throws E;
    }

    /**
     * {@code outcomeOf} names how the answer ended; a thrown exception is classified generically.
     */
    public static <T, E extends Exception> T observe(
            ObservationRegistry registry,
            String name,
            String operation,
            Call<T, E> call,
            Function<T, String> outcomeOf)
            throws E {
        return observe(registry, name, operation, call, outcomeOf, OutboundCall::failureOutcome);
    }

    public static <T, E extends Exception> T observe(
            ObservationRegistry registry,
            String name,
            String operation,
            Call<T, E> call,
            Function<T, String> outcomeOf,
            Function<Throwable, String> failureOf)
            throws E {
        var observation =
                Observation.createNotStarted(name, registry)
                        .lowCardinalityKeyValue(OPERATION, operation)
                        .start();
        try (var ignored = observation.openScope()) {
            var result = call.run();
            observation.lowCardinalityKeyValue(OUTCOME, outcomeOf.apply(result));
            return result;
        } catch (Throwable e) {
            observation.lowCardinalityKeyValue(OUTCOME, failureOf.apply(e));
            throw e;
        } finally {
            observation.stop();
        }
    }

    /**
     * {@code timeout} when anywhere in the chain of causes a timeout is named, else {@code
     * failure}.
     */
    public static String failureOutcome(Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (cause.getClass().getSimpleName().endsWith("TimeoutException")) {
                return TIMEOUT;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return FAILURE;
    }
}
