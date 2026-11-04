package com.unihub.shared.infrastructure.out.observation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The sensitive-data guardrail of observability.md §2 for the calls the application observes itself
 * (Phase 6 checklist 5.4, D73-D74): a span carries the operation and the outcome, never a URL, a
 * header, a body or the text of an exception. The same rule is checked on the spans of a running
 * application, events included, by {@code TracingIT}.
 */
class SpanGuardrailTest {
    private static final String SECRET = "sk_test_dummy_guardrail";
    private static final String URL = "https://api.stripe.com/v1/payment_intents?email=a@b.vn";

    private final RecordingObservations recording = new RecordingObservations();

    @Test
    @DisplayName("a call that answers carries its operation and its outcome, and nothing else")
    void answered() {
        var result =
                OutboundCall.observe(
                        recording.registry(),
                        "unihub.test.call",
                        "POST",
                        () -> "body with " + SECRET,
                        answer -> OutboundCall.SUCCESS);

        assertThat(result).contains(SECRET);
        assertThat(recording.finished()).hasSize(1);
        var span = recording.finished().get(0);
        assertThat(span.name()).isEqualTo("unihub.test.call");
        assertThat(span.value("operation")).isEqualTo("POST");
        assertThat(span.value("outcome")).isEqualTo("success");
        assertThat(span.values()).hasSize(2);
        assertThat(span.everything()).doesNotContain(SECRET);
    }

    @Test
    @DisplayName("a failing call keeps the exception and its message out of the observation")
    void failed() {
        var boom = new IllegalStateException("could not reach " + URL + " with " + SECRET);

        assertThatThrownBy(
                        () ->
                                OutboundCall.observe(
                                        recording.registry(),
                                        "unihub.test.call",
                                        "GET",
                                        () -> {
                                            throw boom;
                                        },
                                        answer -> OutboundCall.SUCCESS))
                .isSameAs(boom);

        var span = recording.finished().get(0);
        assertThat(span.error())
                .as("an error given to an observation becomes a span event")
                .isNull();
        assertThat(span.value("outcome")).isEqualTo("failure");
        assertThat(span.everything()).doesNotContain(SECRET).doesNotContain("stripe.com");
    }

    @Test
    @DisplayName("a timeout anywhere in the chain of causes is a timeout, not a failure")
    void timedOut() {
        var wrapped =
                new IOException(
                        "could not be reached", new HttpTimeoutException("request timed out"));

        assertThatThrownBy(
                        () ->
                                OutboundCall.observe(
                                        recording.registry(),
                                        "unihub.test.call",
                                        "summarize",
                                        () -> {
                                            throw wrapped;
                                        },
                                        answer -> OutboundCall.SUCCESS))
                .isSameAs(wrapped);

        assertThat(recording.finished().get(0).value("outcome")).isEqualTo("timeout");
        assertThat(OutboundCall.failureOutcome(new TimeoutException("x"))).isEqualTo("timeout");
        assertThat(OutboundCall.failureOutcome(new IOException("x"))).isEqualTo("failure");
    }

    @Test
    @DisplayName(
            "the outcome of an answer is the caller's to name, so 'not found' is not a failure")
    void answerOutcomeIsNamedByTheCaller() {
        OutboundCall.observe(
                recording.registry(), "unihub.test.call", "head", () -> 404, code -> "not_found");

        assertThat(recording.finished().get(0).value("outcome")).isEqualTo("not_found");
    }
}
