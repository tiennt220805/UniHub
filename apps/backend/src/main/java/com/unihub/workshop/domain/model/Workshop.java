package com.unihub.workshop.domain.model;

import com.unihub.shared.domain.Version;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.domain.exception.InvalidWorkshopException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Workshop aggregate (Tier A). Invariants mirror the {@code workshops} table CHECK constraints so
 * that the domain and the database reject exactly the same states; the database stays the backstop,
 * never the only guard (architecture.md §4.2). Behavior methods (publish, cancel, reschedule, seat
 * claim) arrive with their use cases.
 */
public final class Workshop {

    private static final int SHORT_TEXT_MAX = 255;
    private static final int BROCHURE_KEY_MAX = 512;

    // workshops.price is NUMERIC(12,2): at most 2 decimals and 10 integer digits.
    private static final int PRICE_MAX_SCALE = 2;
    private static final int PRICE_MAX_INTEGER_DIGITS = 10;

    private final WorkshopId id;
    private final String title;
    private final String description;
    private final String aiSummary;
    private final String speakerName;
    private final String speakerBio;
    private final String location;
    private final Instant startTime;
    private final Instant endTime;
    private final int capacity;
    private final int seatsTaken;
    private final boolean paid;
    private final BigDecimal price;
    private final String brochureS3Key;
    private final WorkshopStatus status;
    private final Version version;

    private Workshop(
            WorkshopId id,
            String title,
            String description,
            String aiSummary,
            String speakerName,
            String speakerBio,
            String location,
            Instant startTime,
            Instant endTime,
            int capacity,
            int seatsTaken,
            boolean paid,
            BigDecimal price,
            String brochureS3Key,
            WorkshopStatus status,
            Version version) {
        this.id = Objects.requireNonNull(id);
        this.title = requireText("title", title, SHORT_TEXT_MAX);
        this.description = description;
        this.aiSummary = aiSummary;
        this.speakerName = requireText("speakerName", speakerName, SHORT_TEXT_MAX);
        this.speakerBio = speakerBio;
        this.location = requireText("location", location, SHORT_TEXT_MAX);
        this.startTime = Objects.requireNonNull(startTime);
        this.endTime = Objects.requireNonNull(endTime);
        this.status = Objects.requireNonNull(status);
        this.version = Objects.requireNonNull(version);
        if (!endTime.isAfter(startTime)) {
            throw new InvalidWorkshopException("endTime must be after startTime");
        }
        if (capacity < 0) {
            throw new InvalidWorkshopException("capacity must not be negative");
        }
        if (seatsTaken < 0 || seatsTaken > capacity) {
            throw new InvalidWorkshopException("seatsTaken must be between 0 and capacity");
        }
        if (paid ? (price == null || price.signum() <= 0) : price != null) {
            throw new InvalidWorkshopException(
                    "a paid workshop needs a positive price; a free one must have none");
        }
        if (price != null) {
            // Trailing zeros are not information ("1.500" == "1.5"), so judge the stripped value.
            var stripped = price.stripTrailingZeros();
            if (stripped.scale() > PRICE_MAX_SCALE
                    || stripped.precision() - stripped.scale() > PRICE_MAX_INTEGER_DIGITS) {
                throw new InvalidWorkshopException(
                        "price must fit NUMERIC(12,2): at most 2 decimals and 10 integer digits");
            }
        }
        if (brochureS3Key != null && brochureS3Key.length() > BROCHURE_KEY_MAX) {
            throw new InvalidWorkshopException("brochureS3Key exceeds " + BROCHURE_KEY_MAX);
        }
        this.capacity = capacity;
        this.seatsTaken = seatsTaken;
        this.paid = paid;
        this.price = price;
        this.brochureS3Key = brochureS3Key;
    }

    /** A new workshop: {@code DRAFT}, no seats taken, no summary or brochure, initial version. */
    public static Workshop draft(
            WorkshopId id,
            String title,
            String description,
            String speakerName,
            String speakerBio,
            String location,
            Instant startTime,
            Instant endTime,
            int capacity,
            boolean paid,
            BigDecimal price) {
        return new Workshop(
                id,
                title,
                description,
                null,
                speakerName,
                speakerBio,
                location,
                startTime,
                endTime,
                capacity,
                0,
                paid,
                price,
                null,
                WorkshopStatus.DRAFT,
                Version.initial());
    }

    /**
     * Rebuilds a persisted workshop; used by the persistence adapter, validates like {@link
     * #draft}.
     */
    public static Workshop restore(
            WorkshopId id,
            String title,
            String description,
            String aiSummary,
            String speakerName,
            String speakerBio,
            String location,
            Instant startTime,
            Instant endTime,
            int capacity,
            int seatsTaken,
            boolean paid,
            BigDecimal price,
            String brochureS3Key,
            WorkshopStatus status,
            Version version) {
        return new Workshop(
                id,
                title,
                description,
                aiSummary,
                speakerName,
                speakerBio,
                location,
                startTime,
                endTime,
                capacity,
                seatsTaken,
                paid,
                price,
                brochureS3Key,
                status,
                version);
    }

    private static String requireText(String field, String value, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new InvalidWorkshopException(field + " is required");
        }
        if (value.length() > maxLength) {
            throw new InvalidWorkshopException(field + " exceeds " + maxLength + " characters");
        }
        return value;
    }

    public WorkshopId id() {
        return id;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public String aiSummary() {
        return aiSummary;
    }

    public String speakerName() {
        return speakerName;
    }

    public String speakerBio() {
        return speakerBio;
    }

    public String location() {
        return location;
    }

    public Instant startTime() {
        return startTime;
    }

    public Instant endTime() {
        return endTime;
    }

    public int capacity() {
        return capacity;
    }

    public int seatsTaken() {
        return seatsTaken;
    }

    public boolean isPaid() {
        return paid;
    }

    public BigDecimal price() {
        return price;
    }

    public String brochureS3Key() {
        return brochureS3Key;
    }

    public WorkshopStatus status() {
        return status;
    }

    public Version version() {
        return version;
    }
}
