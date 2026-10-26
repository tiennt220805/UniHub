package com.unihub.shared.infrastructure;

import com.unihub.UniHubApplication;
import com.unihub.shared.application.port.out.JobLock;
import com.unihub.shared.infrastructure.out.lock.InProcessJobLock;
import com.unihub.shared.security.TestJwts;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerArray;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Two (or more) application instances in one JVM (checklist D22, integration-testing.md §2):
 * separate Spring contexts on random ports, all against the singleton Postgres, Redis and RabbitMQ,
 * <b>each with a {@code RedissonClient} of its own</b> — Redisson ties a lock to the client id plus
 * the thread, so a shared client would make the two "instances" one.
 *
 * <p>What it measures is the lock, not the business outcome: the database check already makes the
 * outcome correct without any lock. A recording decorator wraps the {@link JobLock} of every
 * context and logs each hold as an interval (acquired → released) in one {@link HoldLog} that all
 * contexts write to; a test then asks whether two holds of the same key overlap in time.
 *
 * <p>The decorator and its {@code BeanPostProcessor} are registered programmatically, never as
 * beans: classes under {@code com.unihub} in the test source set are component-scanned into every
 * {@code @SpringBootTest} context. {@link Locking#IN_PROCESS} is the control — each context gets
 * its own {@link InProcessJobLock} in place of the Redisson one. Two instances sharing nothing must
 * then overlap; if they do not, the harness is not separating the instances and a "no overlap"
 * result with Redisson proves nothing.
 */
public final class MultiInstanceHarness implements AutoCloseable {

    public enum Locking {
        /** Production wiring: the Redisson lock of each context's own client. */
        REDISSON,
        /** Control: a lock per context, held in that context's memory. */
        IN_PROCESS
    }

    /** One hold of one key, in {@link System#nanoTime()}. */
    public record Hold(int instance, String key, long startNanos, long endNanos) {}

    /** Every hold and every refusal, across all contexts. */
    public static final class HoldLog {

        private final List<Hold> holds = new CopyOnWriteArrayList<>();
        private final AtomicIntegerArray skips;

        private HoldLog(int instances) {
            this.skips = new AtomicIntegerArray(instances);
        }

        public List<Hold> holds() {
            return List.copyOf(holds);
        }

        public List<Hold> holdsOf(int instance) {
            return holds.stream().filter(h -> h.instance() == instance).toList();
        }

        /** How many times {@code instance} asked for the lock and found it taken. */
        public int skips(int instance) {
            return skips.get(instance);
        }

        public int totalSkips() {
            int total = 0;
            for (int i = 0; i < skips.length(); i++) {
                total += skips.get(i);
            }
            return total;
        }

        /** Pairs of holds of the same key that were in force at the same moment. */
        public List<List<Hold>> overlaps() {
            var overlapping = new ArrayList<List<Hold>>();
            var ordered =
                    holds.stream().sorted(Comparator.comparingLong(Hold::startNanos)).toList();
            for (int i = 0; i < ordered.size(); i++) {
                for (int j = i + 1; j < ordered.size(); j++) {
                    var first = ordered.get(i);
                    var second = ordered.get(j);
                    if (second.startNanos() >= first.endNanos()) {
                        break; // sorted by start: nothing later can overlap `first`
                    }
                    if (first.key().equals(second.key())) {
                        overlapping.add(List.of(first, second));
                    }
                }
            }
            return overlapping;
        }
    }

    private final List<ConfigurableApplicationContext> contexts = new ArrayList<>();
    private final HoldLog log;

    private MultiInstanceHarness(int instances) {
        this.log = new HoldLog(instances);
    }

    /**
     * Starts {@code instances} contexts. {@code holdPadding} is added to every hold right after the
     * lock is taken: a real job body takes milliseconds, too short for two holds to overlap
     * reliably, so the control would flake without it.
     */
    public static MultiInstanceHarness start(
            int instances, Locking locking, Duration holdPadding, String... properties) {
        var harness = new MultiInstanceHarness(instances);
        try {
            for (int i = 0; i < instances; i++) {
                harness.contexts.add(harness.startContext(i, locking, holdPadding, properties));
            }
        } catch (RuntimeException | Error failed) {
            harness.close();
            throw failed;
        }
        return harness;
    }

    private ConfigurableApplicationContext startContext(
            int instance, Locking locking, Duration holdPadding, String[] extra) {
        var properties = new ArrayList<String>();
        properties.add("spring.datasource.url=" + IntegrationTestSupport.POSTGRES.getJdbcUrl());
        properties.add(
                "spring.datasource.username=" + IntegrationTestSupport.POSTGRES.getUsername());
        properties.add(
                "spring.datasource.password=" + IntegrationTestSupport.POSTGRES.getPassword());
        properties.add("unihub.security.jwt.secret=" + TestJwts.SECRET);
        properties.add("server.port=0");
        // a job runs when the test says so, never on its own schedule
        properties.add("unihub.jobs.idempotency-cleanup.interval=PT1H");
        properties.addAll(List.of(extra));
        return new SpringApplicationBuilder(UniHubApplication.class)
                .web(WebApplicationType.SERVLET)
                .properties(properties.toArray(String[]::new))
                .initializers(
                        context ->
                                ((ConfigurableApplicationContext) context)
                                        .getBeanFactory()
                                        .addBeanPostProcessor(
                                                new RecordingJobLocks(
                                                        instance, locking, holdPadding, log)))
                .run();
    }

    public int instances() {
        return contexts.size();
    }

    public ConfigurableApplicationContext context(int instance) {
        return contexts.get(instance);
    }

    public <T> T bean(int instance, Class<T> type) {
        return contexts.get(instance).getBean(type);
    }

    public int port(int instance) {
        return Integer.parseInt(
                contexts.get(instance).getEnvironment().getProperty("local.server.port"));
    }

    public HoldLog log() {
        return log;
    }

    @Override
    public void close() {
        RuntimeException first = null;
        for (var context : contexts) {
            try {
                context.close();
            } catch (RuntimeException e) {
                if (first == null) {
                    first = e;
                }
            }
        }
        contexts.clear();
        if (first != null) {
            throw first;
        }
    }

    /** Wraps (or, for the control, replaces) the context's {@link JobLock}. */
    private static final class RecordingJobLocks implements BeanPostProcessor {

        private final int instance;
        private final Locking locking;
        private final Duration holdPadding;
        private final HoldLog log;

        private RecordingJobLocks(
                int instance, Locking locking, Duration holdPadding, HoldLog log) {
            this.instance = instance;
            this.locking = locking;
            this.holdPadding = holdPadding;
            this.log = log;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (!(bean instanceof JobLock real)) {
                return bean;
            }
            var delegate = locking == Locking.IN_PROCESS ? new InProcessJobLock() : real;
            return new RecordingJobLock(instance, delegate, holdPadding, log);
        }
    }

    private static final class RecordingJobLock implements JobLock {

        private final int instance;
        private final JobLock delegate;
        private final Duration holdPadding;
        private final HoldLog log;

        private RecordingJobLock(
                int instance, JobLock delegate, Duration holdPadding, HoldLog log) {
            this.instance = instance;
            this.delegate = delegate;
            this.holdPadding = holdPadding;
            this.log = log;
        }

        @Override
        public Optional<LockHandle> tryAcquire(String key, Duration leaseTime) {
            var taken = delegate.tryAcquire(key, leaseTime);
            if (taken.isEmpty()) {
                log.skips.incrementAndGet(instance);
                return taken;
            }
            long start = System.nanoTime();
            pad();
            var released = new AtomicBoolean();
            var real = taken.get();
            return Optional.of(
                    () -> {
                        if (released.compareAndSet(false, true)) {
                            // the hold ends before the key is given back, so a genuine next
                            // holder always starts after this end
                            log.holds.add(new Hold(instance, key, start, System.nanoTime()));
                        }
                        real.close();
                    });
        }

        private void pad() {
            try {
                Thread.sleep(holdPadding.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
