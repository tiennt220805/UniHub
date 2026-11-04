package com.unihub.shared.infrastructure.out.metrics;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The value behind a gauge that is read from somewhere slow (PostgreSQL) <b>off the scrape
 * thread</b> (observability.md §4, Phase 6 checklist D76/D77). {@link BackgroundGauges} re-reads
 * the source on a schedule; the gauge only returns the last value, so a scrape never waits on the
 * database: a Master that hangs makes the value stale, never makes {@code /actuator/prometheus}
 * hang and take every other metric with it.
 *
 * <p>The value is {@code NaN} until the first read succeeds, after a read that threw, and once the
 * last good read is older than {@code staleAfter} (a read that hangs never reports back, so age is
 * the only way to see it). {@code NaN} is "unknown", which no alert rule matches; it is never
 * presented as a calm zero.
 */
public final class BackgroundGauge {
    private static final Logger LOG = LoggerFactory.getLogger(BackgroundGauge.class);

    private final String name;
    private final DoubleSupplier source;
    private final long staleAfterNanos;
    private final LongSupplier nanoClock;
    private final AtomicLong valueBits = new AtomicLong(Double.doubleToLongBits(Double.NaN));
    private final AtomicLong readAtNanos = new AtomicLong();
    private volatile boolean everRead;

    BackgroundGauge(
            String name, DoubleSupplier source, Duration staleAfter, LongSupplier nanoClock) {
        this.name = name;
        this.source = source;
        this.staleAfterNanos = staleAfter.toNanos();
        this.nanoClock = nanoClock;
    }

    /** One read of the source; a throw keeps nothing and turns the value into {@code NaN}. */
    void refresh() {
        try {
            var read = source.getAsDouble();
            valueBits.set(Double.doubleToLongBits(read));
            readAtNanos.set(nanoClock.getAsLong());
            everRead = true;
        } catch (RuntimeException e) {
            valueBits.set(Double.doubleToLongBits(Double.NaN));
            LOG.warn("Cannot read {}: {}", name, e.toString());
        }
    }

    /** The last value read; never touches the source. */
    public double value() {
        if (!everRead) {
            return Double.NaN;
        }
        if (nanoClock.getAsLong() - readAtNanos.get() > staleAfterNanos) {
            return Double.NaN;
        }
        return Double.longBitsToDouble(valueBits.get());
    }
}
