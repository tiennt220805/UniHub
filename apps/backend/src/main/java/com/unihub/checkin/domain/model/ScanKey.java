package com.unihub.checkin.domain.model;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

/**
 * What earliest-timestamp-wins looks at in a scan that was judged valid: when it happened on the
 * device and which scan it is.
 *
 * <p>The order is {@code (scannedAt, id)}, and it has to be the order the database would give:
 * {@code scannedAt} is compared at microseconds (all {@code TIMESTAMPTZ} keeps; the JSON carries
 * nanoseconds) and the id as unsigned bytes ({@link UUID#compareTo} reads two signed longs and
 * disagrees with Postgres whenever the first hex digit is 8 or above). It is computed here and
 * nowhere else.
 */
public record ScanKey(ScanId id, Instant scannedAt) {

    public static final Comparator<ScanKey> ORDER =
            Comparator.comparing((ScanKey k) -> k.scannedAt.truncatedTo(ChronoUnit.MICROS))
                    .thenComparing(
                            k -> k.id.value(),
                            (a, b) -> {
                                var high =
                                        Long.compareUnsigned(
                                                a.getMostSignificantBits(),
                                                b.getMostSignificantBits());
                                return high != 0
                                        ? high
                                        : Long.compareUnsigned(
                                                a.getLeastSignificantBits(),
                                                b.getLeastSignificantBits());
                            });

    public ScanKey {
        Objects.requireNonNull(id);
        Objects.requireNonNull(scannedAt);
    }
}
