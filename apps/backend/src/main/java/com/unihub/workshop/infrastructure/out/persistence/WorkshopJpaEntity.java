package com.unihub.workshop.infrastructure.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Row of {@code workshops} (database-schema.md §3.1). Package-private: only the adapter and its
 * mapper see it. {@code version} is Hibernate's optimistic-lock column — the mapper never writes
 * it; {@link JpaWorkshopRepository} compares it explicitly (backend-coding-standards.md §9.2).
 * {@code created_at} is stamped on insert, {@code updated_at} on every write.
 */
@Entity
@Table(name = "workshops")
class WorkshopJpaEntity {

    @Id private UUID id;

    @Column(nullable = false)
    private String title;

    private String description;

    @Column(name = "ai_summary", updatable = false)
    private String aiSummary;

    @Column(name = "speaker_name", nullable = false)
    private String speakerName;

    @Column(name = "speaker_bio")
    private String speakerBio;

    @Column(nullable = false)
    private String location;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "end_time", nullable = false)
    private Instant endTime;

    @Column(nullable = false)
    private int capacity;

    // Written on insert only. After that the column moves solely through the seat-claim/release SQL
    // (JdbcSeatCounter, Phase 2 checklist D3/C15): an organizer edit must never carry a stale
    // count.
    @Column(name = "seats_taken", nullable = false, updatable = false)
    private int seatsTaken;

    @Column(name = "is_paid", nullable = false)
    private boolean paid;

    private BigDecimal price;

    @Column(name = "brochure_s3_key", updatable = false)
    private String brochureS3Key;

    @Column(nullable = false)
    private String status;

    @Version private long version;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    protected WorkshopJpaEntity() {}

    UUID id() {
        return id;
    }

    void id(UUID id) {
        this.id = id;
    }

    String title() {
        return title;
    }

    void title(String title) {
        this.title = title;
    }

    String description() {
        return description;
    }

    void description(String description) {
        this.description = description;
    }

    String aiSummary() {
        return aiSummary;
    }

    void aiSummary(String aiSummary) {
        this.aiSummary = aiSummary;
    }

    String speakerName() {
        return speakerName;
    }

    void speakerName(String speakerName) {
        this.speakerName = speakerName;
    }

    String speakerBio() {
        return speakerBio;
    }

    void speakerBio(String speakerBio) {
        this.speakerBio = speakerBio;
    }

    String location() {
        return location;
    }

    void location(String location) {
        this.location = location;
    }

    Instant startTime() {
        return startTime;
    }

    void startTime(Instant startTime) {
        this.startTime = startTime;
    }

    Instant endTime() {
        return endTime;
    }

    void endTime(Instant endTime) {
        this.endTime = endTime;
    }

    int capacity() {
        return capacity;
    }

    void capacity(int capacity) {
        this.capacity = capacity;
    }

    int seatsTaken() {
        return seatsTaken;
    }

    void seatsTaken(int seatsTaken) {
        this.seatsTaken = seatsTaken;
    }

    boolean paid() {
        return paid;
    }

    void paid(boolean paid) {
        this.paid = paid;
    }

    BigDecimal price() {
        return price;
    }

    void price(BigDecimal price) {
        this.price = price;
    }

    String brochureS3Key() {
        return brochureS3Key;
    }

    void brochureS3Key(String brochureS3Key) {
        this.brochureS3Key = brochureS3Key;
    }

    String status() {
        return status;
    }

    void status(String status) {
        this.status = status;
    }

    long version() {
        return version;
    }

    Instant createdAt() {
        return createdAt;
    }

    Instant updatedAt() {
        return updatedAt;
    }
}
