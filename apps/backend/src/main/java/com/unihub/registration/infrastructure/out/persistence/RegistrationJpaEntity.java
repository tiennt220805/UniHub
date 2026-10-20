package com.unihub.registration.infrastructure.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Row of {@code registrations} (database-schema.md §4.1). Package-private: only the adapter and its
 * mapper see it. {@code version} is Hibernate's optimistic-lock column; the mapper never writes it,
 * {@link JpaRegistrationRepository} compares it explicitly (§9.2).
 */
@Entity
@Table(name = "registrations")
class RegistrationJpaEntity {

    @Id private UUID id;

    @Column(name = "workshop_id", nullable = false)
    private UUID workshopId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "ticket_type", nullable = false)
    private String ticketType;

    @Column(nullable = false)
    private String status;

    @Column(name = "hold_expires_at")
    private Instant holdExpiresAt;

    @Column(name = "ticket_code")
    private String ticketCode;

    @Column(name = "qr_payload")
    private String qrPayload;

    @Version private long version;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    protected RegistrationJpaEntity() {}

    UUID id() {
        return id;
    }

    void id(UUID id) {
        this.id = id;
    }

    UUID workshopId() {
        return workshopId;
    }

    void workshopId(UUID workshopId) {
        this.workshopId = workshopId;
    }

    UUID userId() {
        return userId;
    }

    void userId(UUID userId) {
        this.userId = userId;
    }

    String ticketType() {
        return ticketType;
    }

    void ticketType(String ticketType) {
        this.ticketType = ticketType;
    }

    String status() {
        return status;
    }

    void status(String status) {
        this.status = status;
    }

    Instant holdExpiresAt() {
        return holdExpiresAt;
    }

    void holdExpiresAt(Instant holdExpiresAt) {
        this.holdExpiresAt = holdExpiresAt;
    }

    String ticketCode() {
        return ticketCode;
    }

    void ticketCode(String ticketCode) {
        this.ticketCode = ticketCode;
    }

    String qrPayload() {
        return qrPayload;
    }

    void qrPayload(String qrPayload) {
        this.qrPayload = qrPayload;
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
