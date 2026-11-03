package com.unihub.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

class CorrelationIdProblemAdviceTest {

    private final CorrelationIdProblemAdvice advice = new CorrelationIdProblemAdvice();

    @AfterEach
    void clean() {
        MDC.clear();
    }

    private Object write(Object body) {
        return advice.beforeBodyWrite(body, null, null, null, null, null);
    }

    @Test
    @DisplayName("a ProblemDetail gets the correlation id of the request")
    void problemGetsTheId() {
        MDC.put("correlationId", "0af7651916cd43dd8448eb211c80319c");
        var problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);

        write(problem);

        assertThat(problem.getProperties())
                .containsEntry("correlationId", "0af7651916cd43dd8448eb211c80319c");
    }

    @Test
    @DisplayName("without a correlation id nothing is invented")
    void noIdNoProperty() {
        var problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);

        write(problem);

        assertThat(problem.getProperties()).isNull();
    }

    @Test
    @DisplayName("any other body is left alone")
    void otherBodiesAreUntouched() {
        MDC.put("correlationId", "0af7651916cd43dd8448eb211c80319c");
        var body = java.util.Map.of("a", 1);

        assertThat(write(body)).isSameAs(body);
    }
}
