package com.unihub.shared.infrastructure.tracing;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.DelegatingSpanData;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.List;

/**
 * Keeps the text of an exception out of every exported span (observability.md §2, the
 * sensitive-data guardrail; Phase 6 checklist 5.4). An exception recorded on a span becomes an
 * event with the exception's class, its message and its stack trace; the message of a database
 * refusal carries the row's values ({@code Key (email)=(…)}), and the message of a validation error
 * carries the value that failed. The class stays, so a span still says what kind of failure it was;
 * the text is in the log line of the same trace id, where retention and access are the log's.
 *
 * <p>Wraps every {@link SpanExporter} bean (see {@code TracingConfig}), so whatever carries spans
 * out of the process — OTLP to Tempo, or the in-memory exporter of the tracing ITs — gets the same
 * data.
 */
public final class ScrubbedSpanExporter implements SpanExporter {

    static final AttributeKey<String> MESSAGE = AttributeKey.stringKey("exception.message");
    static final AttributeKey<String> STACKTRACE = AttributeKey.stringKey("exception.stacktrace");

    private final SpanExporter delegate;

    public ScrubbedSpanExporter(SpanExporter delegate) {
        this.delegate = delegate;
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        return delegate.export(spans.stream().map(ScrubbedSpanExporter::scrub).toList());
    }

    @Override
    public CompletableResultCode flush() {
        return delegate.flush();
    }

    @Override
    public CompletableResultCode shutdown() {
        return delegate.shutdown();
    }

    static SpanData scrub(SpanData span) {
        var events = span.getEvents().stream().map(ScrubbedSpanExporter::scrub).toList();
        var status = span.getStatus();
        if (events.equals(span.getEvents()) && status.getDescription().isEmpty()) {
            return span;
        }
        var scrubbedStatus =
                status.getDescription().isEmpty()
                        ? status
                        : StatusData.create(status.getStatusCode(), "");
        return new DelegatingSpanData(span) {
            @Override
            public List<EventData> getEvents() {
                return events;
            }

            @Override
            public StatusData getStatus() {
                return scrubbedStatus;
            }
        };
    }

    private static EventData scrub(EventData event) {
        var attributes = event.getAttributes();
        if (attributes.get(MESSAGE) == null && attributes.get(STACKTRACE) == null) {
            return event;
        }
        AttributesBuilder kept = Attributes.builder();
        attributes.forEach(
                (key, value) -> {
                    if (!key.equals(MESSAGE) && !key.equals(STACKTRACE)) {
                        put(kept, key, value);
                    }
                });
        return EventData.create(event.getEpochNanos(), event.getName(), kept.build());
    }

    @SuppressWarnings("unchecked")
    private static <T> void put(AttributesBuilder builder, AttributeKey<T> key, Object value) {
        builder.put(key, (T) value);
    }
}
