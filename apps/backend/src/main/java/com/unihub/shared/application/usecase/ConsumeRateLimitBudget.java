package com.unihub.shared.application.usecase;

import com.unihub.shared.application.command.ConsumeRateLimitBudgetCommand;
import com.unihub.shared.application.port.in.ConsumeRateLimitBudgetUseCase;
import com.unihub.shared.application.port.out.RateLimitDecision;
import com.unihub.shared.application.port.out.RateLimiter;
import com.unihub.shared.application.result.RateLimitOutcome;
import org.springframework.stereotype.Service;

/**
 * Fail open (distributed-coordination.md §4): when the budget's store cannot answer, the request
 * goes ahead. The seat lock is fail-closed during the same outage, so the one correctness defense
 * still holds; only abuse prevention is degraded. The adapter counts every such answer ({@code
 * unihub_rate_limit_bypassed_total}), so the path is never silent.
 */
@Service
class ConsumeRateLimitBudget implements ConsumeRateLimitBudgetUseCase {

    private final RateLimiter limiter;

    ConsumeRateLimitBudget(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public RateLimitOutcome consume(ConsumeRateLimitBudgetCommand command) {
        return switch (limiter.tryAcquire(command.key(), command.limit(), command.window())) {
            case RateLimitDecision.Allowed allowed -> RateLimitOutcome.permit();
            case RateLimitDecision.Unavailable unavailable -> RateLimitOutcome.permit();
            case RateLimitDecision.Rejected rejected ->
                    RateLimitOutcome.reject(ceilSeconds(rejected));
        };
    }

    private static long ceilSeconds(RateLimitDecision.Rejected rejected) {
        var duration = rejected.retryAfter();
        return duration.getSeconds() + (duration.getNano() > 0 ? 1 : 0);
    }
}
