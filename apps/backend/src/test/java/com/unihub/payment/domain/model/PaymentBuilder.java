package com.unihub.payment.domain.model;

import com.unihub.shared.domain.Version;
import com.unihub.shared.domain.id.PaymentId;
import com.unihub.shared.domain.id.RegistrationId;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Test Data Builder for {@link Payment} (test-data-management.md §1–§2). Baseline: a {@code
 * PENDING} charge of 100000.00 VND initiated at 2026-12-01T09:00Z; it goes through {@link
 * Payment#restore}, so an illegal combination fails exactly as production code would.
 */
public final class PaymentBuilder {
    public static final Instant INITIATED_AT = Instant.parse("2026-12-01T09:00:00Z");
    public static final Instant RESOLVED_AT = Instant.parse("2026-12-01T09:01:00Z");

    private PaymentId id = PaymentId.newId();
    private RegistrationId registrationId = RegistrationId.newId();
    private BigDecimal amount = new BigDecimal("100000.00");
    private String currency = "VND";
    private String gatewayTransactionId;
    private PaymentStatus status = PaymentStatus.PENDING;
    private Instant resolvedAt;
    private Version version = Version.initial();

    private PaymentBuilder() {}

    public static PaymentBuilder aPendingPayment() {
        return new PaymentBuilder();
    }

    public static PaymentBuilder aResolvedPayment(PaymentStatus status) {
        return new PaymentBuilder().withStatus(status).withResolvedAt(RESOLVED_AT);
    }

    public PaymentBuilder withId(PaymentId id) {
        this.id = id;
        return this;
    }

    public PaymentBuilder withRegistrationId(RegistrationId registrationId) {
        this.registrationId = registrationId;
        return this;
    }

    public PaymentBuilder withAmount(BigDecimal amount) {
        this.amount = amount;
        return this;
    }

    public PaymentBuilder withCurrency(String currency) {
        this.currency = currency;
        return this;
    }

    public PaymentBuilder withGatewayTransactionId(String gatewayTransactionId) {
        this.gatewayTransactionId = gatewayTransactionId;
        return this;
    }

    public PaymentBuilder withStatus(PaymentStatus status) {
        this.status = status;
        return this;
    }

    public PaymentBuilder withResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
        return this;
    }

    public PaymentBuilder withVersion(Version version) {
        this.version = version;
        return this;
    }

    public Payment build() {
        return Payment.restore(
                id,
                registrationId,
                amount,
                currency,
                "STRIPE",
                gatewayTransactionId,
                status,
                INITIATED_AT,
                resolvedAt,
                version);
    }
}
