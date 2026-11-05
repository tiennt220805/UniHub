package com.unihub.shared.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.shared.application.command.ConsumeRateLimitBudgetCommand;
import com.unihub.shared.application.port.out.RateLimitDecision;
import com.unihub.shared.application.port.out.RateLimiter;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The fail-open policy of distributed-coordination.md §4 and the {@code Retry-After} rounding. */
class ConsumeRateLimitBudgetTest {

    private static ConsumeRateLimitBudgetCommand command() {
        return new ConsumeRateLimitBudgetCommand("k", 5, Duration.ofSeconds(10));
    }

    private static ConsumeRateLimitBudget with(RateLimitDecision decision) {
        RateLimiter limiter = (key, limit, window) -> decision;
        return new ConsumeRateLimitBudget(limiter);
    }

    @Test
    @DisplayName("a permit was used: the request may go ahead")
    void allowed_permits() {
        assertThat(with(new RateLimitDecision.Allowed()).consume(command()).allowed()).isTrue();
    }

    @Test
    @DisplayName("the store did not answer: fail open, the request may go ahead")
    void unavailable_failsOpen() {
        assertThat(with(new RateLimitDecision.Unavailable("timeout")).consume(command()).allowed())
                .isTrue();
    }

    @Test
    @DisplayName("budget spent: rejected, Retry-After in whole seconds rounded up")
    void rejected_roundsRetryAfterUp() {
        var outcome =
                with(new RateLimitDecision.Rejected(Duration.ofMillis(1_200))).consume(command());

        assertThat(outcome.allowed()).isFalse();
        assertThat(outcome.retryAfterSeconds()).isEqualTo(2);
    }

    @Test
    @DisplayName("Retry-After is never below 1 second")
    void rejected_neverBelowOne() {
        var outcome = with(new RateLimitDecision.Rejected(Duration.ZERO)).consume(command());

        assertThat(outcome.retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    @DisplayName("the command refuses a blank key, a zero limit and a zero window")
    void command_validates() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new ConsumeRateLimitBudgetCommand(" ", 5, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new ConsumeRateLimitBudgetCommand("k", 0, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new ConsumeRateLimitBudgetCommand("k", 1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
