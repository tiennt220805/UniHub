package com.unihub.payment.infrastructure.out.persistence;

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
 * Row of {@code payments} (database-schema.md §5.1). Package-private; {@code version} is
 * Hibernate's optimistic-lock column and {@link JpaPaymentRepository} compares it explicitly
 * (backend-coding-standards.md §9.2).
 */
@Entity
@Table(name = "payments")
class PaymentJpaEntity {

    @Id private UUID id;

    @Column(name = "registration_id", nullable = false, updatable = false)
    private UUID registrationId;

    @Column(nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Column(nullable = false, updatable = false)
    private String gateway;

    @Column(name = "gateway_transaction_id")
    private String gatewayTransactionId;

    @Column(nullable = false)
    private String status;

    @Column(name = "initiated_at", nullable = false, updatable = false)
    private Instant initiatedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Version private long version;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    protected PaymentJpaEntity() {}

    UUID id() {
        return id;
    }

    void id(UUID id) {
        this.id = id;
    }

    UUID registrationId() {
        return registrationId;
    }

    void registrationId(UUID registrationId) {
        this.registrationId = registrationId;
    }

    BigDecimal amount() {
        return amount;
    }

    void amount(BigDecimal amount) {
        this.amount = amount;
    }

    String currency() {
        return currency;
    }

    void currency(String currency) {
        this.currency = currency;
    }

    String gateway() {
        return gateway;
    }

    void gateway(String gateway) {
        this.gateway = gateway;
    }

    String gatewayTransactionId() {
        return gatewayTransactionId;
    }

    void gatewayTransactionId(String gatewayTransactionId) {
        this.gatewayTransactionId = gatewayTransactionId;
    }

    String status() {
        return status;
    }

    void status(String status) {
        this.status = status;
    }

    Instant initiatedAt() {
        return initiatedAt;
    }

    void initiatedAt(Instant initiatedAt) {
        this.initiatedAt = initiatedAt;
    }

    Instant resolvedAt() {
        return resolvedAt;
    }

    void resolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    long version() {
        return version;
    }
}
