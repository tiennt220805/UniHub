package com.unihub.shared.application.port.in;

import com.unihub.shared.application.command.ConsumeRateLimitBudgetCommand;
import com.unihub.shared.application.result.RateLimitOutcome;

/** Charges one request to a rate-limit budget (distributed-coordination.md §3). */
public interface ConsumeRateLimitBudgetUseCase {

    RateLimitOutcome consume(ConsumeRateLimitBudgetCommand command);
}
