package com.unihub.shared.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The concurrent half of the INV-11 evidence (Phase 4 checklist D41, M-40): the same message
 * handled by several threads at once. A consumer is called from the broker's listener thread, so
 * the test calls its {@code port/in} from threads of its own after a latch, and <b>measures</b>
 * that the calls overlapped in time: a harness that quietly ran them one after the other would
 * prove nothing about a race. Each call's interval is taken in {@link System#nanoTime()} after the
 * latch is released.
 *
 * <p>What a losing call may do is stated by the test, not tolerated in general: {@link
 * Outcome#assertOnlyFailuresOf} fails on any other exception, so a mutation that turns a no-op into
 * a real error cannot hide behind a broad catch.
 */
public final class Redeliveries {

    private Redeliveries() {}

    /** One call: its interval, and what it threw, if anything. */
    public record Call(long startNanos, long endNanos, Throwable failure) {}

    /** All the calls of one round. */
    public record Outcome(List<Call> calls) {

        /** Every call began before any call ended: the calls were in flight at the same time. */
        public boolean overlapped() {
            var lastStart = calls.stream().mapToLong(Call::startNanos).max().orElseThrow();
            var firstEnd = calls.stream().mapToLong(Call::endNanos).min().orElseThrow();
            return lastStart < firstEnd;
        }

        public List<Throwable> failures() {
            return calls.stream().map(Call::failure).filter(f -> f != null).toList();
        }

        /** The calls overlapped, and any that failed failed only with one of these types. */
        @SafeVarargs
        public final Outcome assertOnlyFailuresOf(Class<? extends Throwable>... allowed) {
            assertThat(overlapped()).as("the calls were in flight at the same time").isTrue();
            assertThat(failures())
                    .as("a losing call may only fail with a transient, retryable error")
                    .allSatisfy(
                            failure ->
                                    assertThat(allowed)
                                            .as(failure.toString())
                                            .anyMatch(type -> type.isInstance(failure)));
            return this;
        }

        public boolean anyFailed() {
            return !failures().isEmpty();
        }
    }

    /** Runs {@code call} on {@code threads} threads released together. */
    public static Outcome concurrently(int threads, Runnable call) {
        var pool = Executors.newFixedThreadPool(threads);
        try {
            var ready = new CountDownLatch(threads);
            var go = new CountDownLatch(1);
            var futures = new ArrayList<java.util.concurrent.Future<Call>>();
            for (int i = 0; i < threads; i++) {
                futures.add(
                        pool.submit(
                                () -> {
                                    ready.countDown();
                                    go.await();
                                    var start = System.nanoTime();
                                    Throwable failure = null;
                                    try {
                                        call.run();
                                    } catch (Throwable t) {
                                        failure = t;
                                    }
                                    return new Call(start, System.nanoTime(), failure);
                                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            var calls = new ArrayList<Call>();
            for (var future : futures) {
                calls.add(future.get(60, TimeUnit.SECONDS));
            }
            return new Outcome(calls);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException(e);
        } finally {
            pool.shutdownNow();
        }
    }
}
