package com.unihub.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.shared.domain.DomainException;
import com.unihub.shared.domain.ProblemKind;
import com.unihub.shared.domain.RetryableUnavailable;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

class GlobalProblemHandlerRetryAfterTest {

    private final GlobalProblemHandler handler = new GlobalProblemHandler();
    private final MockHttpServletRequest request =
            new MockHttpServletRequest("POST", "/api/v1/registrations");

    private static final class Busy extends DomainException implements RetryableUnavailable {
        private final Duration retryAfter;

        Busy(Duration retryAfter) {
            super("busy");
            this.retryAfter = retryAfter;
        }

        @Override
        public ProblemKind kind() {
            return ProblemKind.UNAVAILABLE;
        }

        @Override
        public Duration retryAfter() {
            return retryAfter;
        }
    }

    private static final class Down extends DomainException {
        Down() {
            super("down");
        }

        @Override
        public ProblemKind kind() {
            return ProblemKind.UNAVAILABLE;
        }
    }

    @Test
    @DisplayName("a retryable UNAVAILABLE answers 503 with Retry-After in whole seconds")
    void retryableUnavailable_hasRetryAfter() {
        var response = handler.handleDomain(new Busy(Duration.ofSeconds(2)), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("2");
    }

    @Test
    @DisplayName("a sub-second retry hint is rounded up to one second, never 0")
    void subSecondRetryAfter_isOneSecond() {
        var response = handler.handleDomain(new Busy(Duration.ofMillis(200)), request);

        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
    }

    @Test
    @DisplayName("an UNAVAILABLE that is not retryable carries no Retry-After")
    void plainUnavailable_hasNoRetryAfter() {
        var response = handler.handleDomain(new Down(), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders()).doesNotContainKey(HttpHeaders.RETRY_AFTER);
    }
}
