package com.unihub.shared.infrastructure.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;

/**
 * A {@link HealthIndicator} that runs its delegate inside a {@link BoundedProbe} and maps any
 * non-{@code UP} answer to {@code failureStatus}. The details of a failed delegate (a connection
 * URL, a driver message) are dropped: only the exception class or the reason of the time limit is
 * reported.
 */
final class BoundedHealthIndicator implements HealthIndicator {

    private final BoundedProbe probe;
    private final HealthIndicator delegate;
    private final Status failureStatus;

    BoundedHealthIndicator(BoundedProbe probe, HealthIndicator delegate, Status failureStatus) {
        this.probe = probe;
        this.delegate = delegate;
        this.failureStatus = failureStatus;
    }

    @Override
    public Health health() {
        return probe.run(
                () -> {
                    var result = delegate.health();
                    if (Status.UP.equals(result.getStatus())) {
                        return Health.up().build();
                    }
                    var error = result.getDetails().get("error");
                    return Health.status(failureStatus)
                            .withDetail(
                                    "error", error == null ? "not available" : errorClass(error))
                            .build();
                });
    }

    private static String errorClass(Object error) {
        // Boot's indicators report "<class>: <message>"; the message may carry an address.
        var text = String.valueOf(error);
        int colon = text.indexOf(':');
        return colon > 0 ? text.substring(0, colon) : text;
    }

    void shutdown() {
        probe.shutdown();
    }
}
