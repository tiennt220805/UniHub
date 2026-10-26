package com.unihub.shared.infrastructure.in.scheduling;

import org.springframework.context.ApplicationContext;

/**
 * Lets a test in another package run one tick of a scheduler by hand: the schedulers are
 * package-private triggers (backend-coding-standards.md §9.4), and a test that proves what a tick
 * does must not wait for the schedule. Not a bean: no annotation, never scanned.
 */
public final class SchedulerTicks {

    private SchedulerTicks() {}

    public static void idempotencyCleanup(ApplicationContext context) {
        context.getBean(IdempotencyCleanupScheduler.class).sweep();
    }
}
