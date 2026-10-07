package com.unihub.workshop.domain.model;

import com.unihub.shared.domain.Version;
import com.unihub.shared.domain.id.WorkshopId;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Test Data Builder for {@link Workshop} (test-data-management.md §1–§2). Baseline: a legal, free
 * workshop with {@code capacity = 100}, {@code seatsTaken = 0}, {@code version = 0}; the state is
 * in the factory name, a test overrides only the field it is about. The builder decides no outcome:
 * it goes through {@link Workshop#restore}, so an illegal combination fails exactly as production
 * code would.
 */
public final class WorkshopBuilder {

    private WorkshopId id = WorkshopId.newId();
    private String title = "Intro to Kubernetes";
    private String description = "A hands-on introduction.";
    private String aiSummary;
    private String speakerName = "Ada Lovelace";
    private String speakerBio = "Pioneer of computing.";
    private String location = "Room 101";
    private Instant startTime = Instant.parse("2026-12-01T09:00:00Z");
    private Instant endTime = Instant.parse("2026-12-01T11:00:00Z");
    private int capacity = 100;
    private int seatsTaken = 0;
    private boolean paid = false;
    private BigDecimal price;
    private String brochureS3Key;
    private WorkshopStatus status = WorkshopStatus.DRAFT;
    private Version version = Version.initial();

    private WorkshopBuilder() {}

    public static WorkshopBuilder aDraftWorkshop() {
        return new WorkshopBuilder();
    }

    public static WorkshopBuilder aPublishedWorkshop() {
        return new WorkshopBuilder().withStatus(WorkshopStatus.PUBLISHED);
    }

    /** A published, paid workshop with a baseline price of 100000.00. */
    public static WorkshopBuilder aPaidWorkshop() {
        return aPublishedWorkshop().withPaid(true).withPrice(new BigDecimal("100000.00"));
    }

    public static WorkshopBuilder aCancelledWorkshop() {
        return new WorkshopBuilder().withStatus(WorkshopStatus.CANCELLED);
    }

    public static WorkshopBuilder aCompletedWorkshop() {
        return new WorkshopBuilder().withStatus(WorkshopStatus.COMPLETED);
    }

    public WorkshopBuilder withId(WorkshopId id) {
        this.id = id;
        return this;
    }

    public WorkshopBuilder withTitle(String title) {
        this.title = title;
        return this;
    }

    public WorkshopBuilder withDescription(String description) {
        this.description = description;
        return this;
    }

    public WorkshopBuilder withAiSummary(String aiSummary) {
        this.aiSummary = aiSummary;
        return this;
    }

    public WorkshopBuilder withSpeakerName(String speakerName) {
        this.speakerName = speakerName;
        return this;
    }

    public WorkshopBuilder withSpeakerBio(String speakerBio) {
        this.speakerBio = speakerBio;
        return this;
    }

    public WorkshopBuilder withLocation(String location) {
        this.location = location;
        return this;
    }

    public WorkshopBuilder withStartTime(Instant startTime) {
        this.startTime = startTime;
        return this;
    }

    public WorkshopBuilder withEndTime(Instant endTime) {
        this.endTime = endTime;
        return this;
    }

    public WorkshopBuilder withCapacity(int capacity) {
        this.capacity = capacity;
        return this;
    }

    public WorkshopBuilder withSeatsTaken(int seatsTaken) {
        this.seatsTaken = seatsTaken;
        return this;
    }

    public WorkshopBuilder withPaid(boolean paid) {
        this.paid = paid;
        return this;
    }

    public WorkshopBuilder withPrice(BigDecimal price) {
        this.price = price;
        return this;
    }

    public WorkshopBuilder withBrochureS3Key(String brochureS3Key) {
        this.brochureS3Key = brochureS3Key;
        return this;
    }

    public WorkshopBuilder withStatus(WorkshopStatus status) {
        this.status = status;
        return this;
    }

    public WorkshopBuilder withVersion(Version version) {
        this.version = version;
        return this;
    }

    public Workshop build() {
        return Workshop.restore(
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
}
