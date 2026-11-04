package com.unihub.shared.infrastructure.tracing;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ScrubbedSpanExporterTest {
    private static final String ROW = "Key (email)=(an@example.vn) already exists.";

    private final InMemorySpanExporter exported = InMemorySpanExporter.create();
    private final SdkTracerProvider provider =
            SdkTracerProvider.builder()
                    .addSpanProcessor(
                            SimpleSpanProcessor.create(new ScrubbedSpanExporter(exported)))
                    .build();

    @Test
    @DisplayName("an exception on a span keeps its class and loses its message and stack trace")
    void exceptionText_isRemoved() {
        var span = provider.get("test").spanBuilder("query").startSpan();
        span.setAttribute("peer.service", "unihub-master");
        span.recordException(new IllegalStateException(ROW));
        span.setStatus(StatusCode.ERROR, ROW);
        span.end();

        var data = exported.getFinishedSpanItems().get(0);
        assertThat(data.getEvents()).hasSize(1);
        var event = data.getEvents().get(0);
        assertThat(event.getName()).isEqualTo("exception");
        assertThat(event.getAttributes().get(AttributeKey.stringKey("exception.type")))
                .isEqualTo("java.lang.IllegalStateException");
        assertThat(event.getAttributes().get(ScrubbedSpanExporter.MESSAGE)).isNull();
        assertThat(event.getAttributes().get(ScrubbedSpanExporter.STACKTRACE)).isNull();
        assertThat(data.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(data.getStatus().getDescription()).isEmpty();
        assertThat(data.getAttributes().asMap().toString()).contains("unihub-master");
        assertThat(data.getName() + data.getEvents() + data.getStatus()).doesNotContain("email");
    }

    @Test
    @DisplayName("a span without an exception is exported as it is")
    void cleanSpan_isUntouched() {
        var span = provider.get("test").spanBuilder("query").startSpan();
        span.addEvent("before AuthorizationFilter");
        span.end();

        var data = exported.getFinishedSpanItems().get(0);
        assertThat(data.getEvents())
                .extracting("name")
                .containsExactly("before AuthorizationFilter");
    }
}
