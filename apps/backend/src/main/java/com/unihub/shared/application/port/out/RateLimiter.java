package com.unihub.shared.application.port.out;

import java.time.Duration;

/**
 * One request budget shared by every instance (distributed-coordination.md §3, INV-17): a key that
 * may be used {@code limit} times in any {@code window}. The count lives outside the process, so
 * the budget is the same whichever pod serves the request; a per-instance limiter would let a
 * client that is routed across N pods use N times the budget.
 *
 * <p>Contract, shared by every adapter:
 *
 * <ul>
 *   <li>Each call uses one permit of the key's budget when it answers {@link
 *       RateLimitDecision.Allowed}; a {@link RateLimitDecision.Rejected} call uses none.
 *   <li>It never throws and never waits longer than a short, adapter-defined time: when the backing
 *       store cannot answer it reports {@link RateLimitDecision.Unavailable} and lets the caller
 *       decide what that means (the use case fails open, §4).
 * </ul>
 */
public interface RateLimiter {

    RateLimitDecision tryAcquire(String key, int limit, Duration window);
}
