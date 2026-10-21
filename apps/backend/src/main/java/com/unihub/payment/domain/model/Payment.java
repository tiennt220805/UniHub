package com.unihub.payment.domain.model;

import com.unihub.payment.domain.event.PaymentDomainEvent;
import com.unihub.payment.domain.event.PaymentSucceeded;
import com.unihub.payment.domain.exception.InvalidPaymentStateException;
import com.unihub.shared.domain.Version;
import com.unihub.shared.domain.id.PaymentId;
import com.unihub.shared.domain.id.RegistrationId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Payment aggregate (Tier A). A payment starts {@code PENDING} and is resolved exactly once, to
 * {@code SUCCEEDED}, {@code FAILED} or {@code CANCELLED}, by whichever of the charge itself, the
 * webhook or the reconciliation sweep gets there first (payment.md §6). Every resolved status
 * rejects every further transition: no crossing between resolved states and no way back to {@code
 * PENDING} (test-design-techniques.md §2). The transitions out of {@code SUCCEEDED}/{@code
 * CANCELLED}/{@code FAILED} toward {@code REFUND_REQUIRED} belong to the compensation branch and
 * are added with it.
 */
public final class Payment {
    /** {@code payments.currency} defaults to VND (database-schema.md §5.1). */
    public static final String DEFAULT_CURRENCY = "VND";

    private static final String DEFAULT_GATEWAY = "STRIPE";
    private static final int AMOUNT_MAX_SCALE = 2;
    private static final int AMOUNT_MAX_INTEGER_DIGITS = 10;

    private final PaymentId id;
    private final RegistrationId registrationId;
    private final BigDecimal amount;
    private final String currency;
    private final String gateway;
    private String gatewayTransactionId;
    private final List<PaymentDomainEvent> events = new ArrayList<>();
    private PaymentStatus status;
    private final Instant initiatedAt;
    private Instant resolvedAt;
    private final Version version;

    private Payment(
            PaymentId id,
            RegistrationId registrationId,
            BigDecimal amount,
            String currency,
            String gateway,
            String gatewayTransactionId,
            PaymentStatus status,
            Instant initiatedAt,
            Instant resolvedAt,
            Version version) {
        this.id = Objects.requireNonNull(id);
        this.registrationId = Objects.requireNonNull(registrationId);
        this.amount = requireAmount(amount);
        this.currency = requireCurrency(currency);
        this.gateway = Objects.requireNonNull(gateway);
        this.gatewayTransactionId = gatewayTransactionId;
        this.status = Objects.requireNonNull(status);
        this.initiatedAt = Objects.requireNonNull(initiatedAt);
        this.resolvedAt = resolvedAt;
        this.version = Objects.requireNonNull(version);
        if (status == PaymentStatus.PENDING && resolvedAt != null) {
            throw new InvalidPaymentStateException("a PENDING payment cannot have a resolved_at");
        }
    }

    /** A charge about to be attempted: {@code PENDING}, initial version, no gateway id yet. */
    public static Payment initiate(
            PaymentId id,
            RegistrationId registrationId,
            BigDecimal amount,
            String currency,
            Instant now) {
        return new Payment(
                id,
                registrationId,
                amount,
                currency,
                DEFAULT_GATEWAY,
                null,
                PaymentStatus.PENDING,
                now,
                null,
                Version.initial());
    }

    /** Rebuilds a persisted payment; validates like {@link #initiate}. */
    public static Payment restore(
            PaymentId id,
            RegistrationId registrationId,
            BigDecimal amount,
            String currency,
            String gateway,
            String gatewayTransactionId,
            PaymentStatus status,
            Instant initiatedAt,
            Instant resolvedAt,
            Version version) {
        return new Payment(
                id,
                registrationId,
                amount,
                currency,
                gateway,
                gatewayTransactionId,
                status,
                initiatedAt,
                resolvedAt,
                version);
    }

    /**
     * Stores the gateway's id for this payment, once (payment.md §1). The confirm is only ever sent
     * after this, which is why a {@code PENDING} payment without an id provably moved no money.
     */
    public void attachGatewayTransactionId(String gatewayId) {
        if (gatewayId == null || gatewayId.isBlank()) {
            throw new InvalidPaymentStateException("a gateway transaction id cannot be blank");
        }
        if (status != PaymentStatus.PENDING) {
            throw new InvalidPaymentStateException(id, status, "take a gateway transaction id");
        }
        if (gatewayTransactionId != null) {
            throw new InvalidPaymentStateException(
                    "payment " + id.value() + " already has a gateway transaction id");
        }
        this.gatewayTransactionId = gatewayId;
    }

    /** Records {@link PaymentSucceeded}: the registration may now be confirmed. */
    public void succeed(Instant now) {
        resolve(PaymentStatus.SUCCEEDED, now, "succeed");
        events.add(new PaymentSucceeded(id, registrationId, now));
    }

    public void fail(Instant now) {
        resolve(PaymentStatus.FAILED, now, "fail");
    }

    public void cancel(Instant now) {
        resolve(PaymentStatus.CANCELLED, now, "be cancelled");
    }

    private void resolve(PaymentStatus target, Instant now, String attempted) {
        Objects.requireNonNull(now);
        if (status != PaymentStatus.PENDING) {
            throw new InvalidPaymentStateException(id, status, attempted);
        }
        this.status = target;
        this.resolvedAt = now;
    }

    private static BigDecimal requireAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new InvalidPaymentStateException("a payment amount must be positive");
        }
        var stripped = amount.stripTrailingZeros();
        if (stripped.scale() > AMOUNT_MAX_SCALE
                || stripped.precision() - stripped.scale() > AMOUNT_MAX_INTEGER_DIGITS) {
            throw new InvalidPaymentStateException(
                    "amount must fit NUMERIC(12,2): at most 2 decimals and 10 integer digits");
        }
        return amount;
    }

    private static String requireCurrency(String currency) {
        if (currency == null || currency.length() != 3) {
            throw new InvalidPaymentStateException("currency must be a 3-letter code");
        }
        return currency;
    }

    /** Returns and clears the events recorded since the aggregate was loaded or last pulled. */
    public List<PaymentDomainEvent> pullEvents() {
        var pulled = List.copyOf(events);
        events.clear();
        return pulled;
    }

    public PaymentId id() {
        return id;
    }

    public RegistrationId registrationId() {
        return registrationId;
    }

    public BigDecimal amount() {
        return amount;
    }

    public String currency() {
        return currency;
    }

    public String gateway() {
        return gateway;
    }

    /** {@code null} until the gateway has answered. */
    public String gatewayTransactionId() {
        return gatewayTransactionId;
    }

    public PaymentStatus status() {
        return status;
    }

    public Instant initiatedAt() {
        return initiatedAt;
    }

    /** {@code null} while {@code PENDING}. */
    public Instant resolvedAt() {
        return resolvedAt;
    }

    public Version version() {
        return version;
    }
}
