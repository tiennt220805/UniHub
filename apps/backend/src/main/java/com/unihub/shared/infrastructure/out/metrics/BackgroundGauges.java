package com.unihub.shared.infrastructure.out.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Registers the gauges whose source is PostgreSQL and re-reads them in the background, every 10
 * seconds (observability.md §4, Phase 6 checklist D76). <b>One small executor for the whole
 * application</b>, not a thread per gauge: the number of threads is the number of pool connections
 * that metrics can ever pin. A read that hangs on a frozen Master (the JDBC query timeout does not
 * unblock a read on a server that no longer answers) holds one of two threads and one connection,
 * never the pool.
 *
 * <p>{@code unihub.observability.background-refresh} (default {@code true}) switches the refresh
 * off; the series are still registered, at NaN. Integration tests switch it off by default for the
 * same reason they switch the listeners off: Spring caches one context per test configuration for
 * the whole run, and every idle cached context would keep reading from the shared database.
 */
@Component
public class BackgroundGauges {
    /** How often a source is re-read; also the debounce of the catalog (D76: 10 s). */
    public static final Duration INTERVAL = Duration.ofSeconds(10);

    private final boolean enabled;
    private final ScheduledExecutorService executor;

    @Autowired
    public BackgroundGauges(
            @Value("${unihub.observability.background-refresh:true}") boolean enabled) {
        this.enabled = enabled;
        this.executor =
                Executors.newScheduledThreadPool(
                        2,
                        task -> {
                            var thread = new Thread(task, "metric-refresh");
                            thread.setDaemon(true);
                            return thread;
                        });
    }

    /**
     * Registers {@code name} on {@code registry}; its first read starts at once, off this thread.
     */
    public BackgroundGauge register(
            MeterRegistry registry,
            String name,
            String description,
            String baseUnit,
            DoubleSupplier source) {
        return register(registry, name, description, baseUnit, source, INTERVAL);
    }

    public BackgroundGauge register(
            MeterRegistry registry,
            String name,
            String description,
            String baseUnit,
            DoubleSupplier source,
            Duration interval) {
        var gauge = new BackgroundGauge(name, source, interval.multipliedBy(6), System::nanoTime);
        Gauge.builder(name, gauge, BackgroundGauge::value)
                .description(description)
                .baseUnit(baseUnit)
                .strongReference(true)
                .register(registry);
        if (enabled) {
            executor.scheduleWithFixedDelay(
                    gauge::refresh, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
        }
        return gauge;
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}
