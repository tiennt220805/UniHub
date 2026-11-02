package com.unihub.shared.infrastructure.health;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

/**
 * Runs one dependency check on a thread of its own and gives up after a hard time limit
 * (observability.md §5). A paused Redis, a broker that does not finish its handshake or an
 * exhausted connection pool would otherwise hold the health call, and with it every scrape or probe
 * that reads it, for as long as the client's own timeout allows. At most one check runs at a time:
 * while a hung one is still running, the next call answers at once with the failure status instead
 * of piling threads up behind a dead dependency.
 */
final class BoundedProbe {

    private final String name;
    private final Duration timeout;
    private final Status failureStatus;
    private final ThreadPoolExecutor executor;

    BoundedProbe(String name, Duration timeout, Status failureStatus) {
        this.name = name;
        this.timeout = timeout;
        this.failureStatus = failureStatus;
        this.executor =
                new ThreadPoolExecutor(
                        1,
                        1,
                        0L,
                        TimeUnit.MILLISECONDS,
                        new SynchronousQueue<>(),
                        task -> {
                            var thread = new Thread(task, "health-probe-" + name);
                            thread.setDaemon(true);
                            return thread;
                        });
    }

    /**
     * The check's own result, or {@code failureStatus} when it throws, times out or cannot start.
     */
    Health run(Supplier<Health> check) {
        try {
            var future = executor.submit(check::get);
            try {
                return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                return failed("no answer within " + timeout.toMillis() + " ms");
            } catch (ExecutionException e) {
                return failed(e.getCause().getClass().getSimpleName());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return failed("interrupted");
            }
        } catch (RejectedExecutionException e) {
            return failed("the previous check of " + name + " is still running");
        }
    }

    private Health failed(String reason) {
        return Health.status(failureStatus).withDetail("error", reason).build();
    }

    void shutdown() {
        executor.shutdownNow();
    }
}
