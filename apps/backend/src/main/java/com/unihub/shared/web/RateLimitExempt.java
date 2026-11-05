package com.unihub.shared.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an operation the rate limiter never charges, so {@link RateLimitedResponses} does not
 * declare a {@code 429} on it. The behaviour is {@code RateLimitGate}'s own exemption (the payment
 * webhook, distributed-coordination.md §3); this marker keeps the contract in step with it.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimitExempt {}
