package com.unihub.registration.domain.model;

import com.unihub.registration.domain.exception.InvalidRegistrationStateException;
import com.unihub.shared.domain.Version;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.domain.id.WorkshopId;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Registration aggregate (Tier A). The state machine is test-design-techniques.md §2: {@code
 * RESERVED → CONFIRMED | CANCELLED | EXPIRED} and {@code CONFIRMED → CANCELLED}; nothing leaves
 * {@code CANCELLED} or {@code EXPIRED}, and an {@code EXPIRED} hold never converts because its seat
 * may already belong to someone else. The invariant {@code RESERVED ⇔ hold_expires_at} mirrors the
 * table's CHECK, so the domain and the database reject the same states.
 *
 * <p>The seat counter is not here: the {@code workshops} update commits in the same transaction but
 * belongs to Workshop (database-schema.md §4.1). {@code ticket_code}/{@code qr_payload} are carried
 * but never produced in Phase 2 (they stay {@code null} until ticket issuance exists).
 */
public final class Registration {

    private final RegistrationId id;
    private final WorkshopId workshopId;
    private final UserId userId;
    private final TicketType ticketType;
    private RegistrationStatus status;
    private Instant holdExpiresAt;
    private final String ticketCode;
    private final String qrPayload;
    private final Version version;

    private Registration(
            RegistrationId id,
            WorkshopId workshopId,
            UserId userId,
            TicketType ticketType,
            RegistrationStatus status,
            Instant holdExpiresAt,
            String ticketCode,
            String qrPayload,
            Version version) {
        this.id = Objects.requireNonNull(id);
        this.workshopId = Objects.requireNonNull(workshopId);
        this.userId = Objects.requireNonNull(userId);
        this.ticketType = Objects.requireNonNull(ticketType);
        this.status = Objects.requireNonNull(status);
        this.holdExpiresAt = holdExpiresAt;
        this.ticketCode = ticketCode;
        this.qrPayload = qrPayload;
        this.version = Objects.requireNonNull(version);
        requireHoldMatchesStatus(status, holdExpiresAt);
        if (status == RegistrationStatus.RESERVED && ticketType != TicketType.PAID) {
            throw new InvalidRegistrationStateException("only a paid ticket can be RESERVED");
        }
    }

    /** A paid registration: the seat is held until {@code now + holdDuration}. */
    public static Registration reserve(
            RegistrationId id,
            WorkshopId workshopId,
            UserId userId,
            Instant now,
            Duration holdDuration) {
        Objects.requireNonNull(now);
        if (holdDuration == null || holdDuration.isNegative() || holdDuration.isZero()) {
            throw new InvalidRegistrationStateException("the seat hold must last a positive time");
        }
        return new Registration(
                id,
                workshopId,
                userId,
                TicketType.PAID,
                RegistrationStatus.RESERVED,
                now.plus(holdDuration),
                null,
                null,
                Version.initial());
    }

    /** A free registration: confirmed at once, there is nothing to wait for. */
    public static Registration confirmFree(
            RegistrationId id, WorkshopId workshopId, UserId userId) {
        return new Registration(
                id,
                workshopId,
                userId,
                TicketType.FREE,
                RegistrationStatus.CONFIRMED,
                null,
                null,
                null,
                Version.initial());
    }

    /** Rebuilds a persisted registration; validates like the factories. */
    public static Registration restore(
            RegistrationId id,
            WorkshopId workshopId,
            UserId userId,
            TicketType ticketType,
            RegistrationStatus status,
            Instant holdExpiresAt,
            String ticketCode,
            String qrPayload,
            Version version) {
        return new Registration(
                id,
                workshopId,
                userId,
                ticketType,
                status,
                holdExpiresAt,
                ticketCode,
                qrPayload,
                version);
    }

    /** Payment completed while the hold was still ours. */
    public void confirm() {
        requireStatus(RegistrationStatus.RESERVED, "be confirmed");
        this.status = RegistrationStatus.CONFIRMED;
        this.holdExpiresAt = null;
    }

    /** Cancels a held or confirmed registration. */
    public void cancel() {
        if (!status.occupiesSeat()) {
            throw new InvalidRegistrationStateException(id, status, "be cancelled");
        }
        this.status = RegistrationStatus.CANCELLED;
        this.holdExpiresAt = null;
    }

    /** The hold lapsed. Only a {@code RESERVED} registration has a hold to lapse. */
    public void expire() {
        requireStatus(RegistrationStatus.RESERVED, "expire");
        this.status = RegistrationStatus.EXPIRED;
        this.holdExpiresAt = null;
    }

    private void requireStatus(RegistrationStatus required, String attempted) {
        if (status != required) {
            throw new InvalidRegistrationStateException(id, status, attempted);
        }
    }

    private static void requireHoldMatchesStatus(RegistrationStatus status, Instant holdExpiresAt) {
        if ((status == RegistrationStatus.RESERVED) != (holdExpiresAt != null)) {
            throw new InvalidRegistrationStateException(
                    "hold_expires_at must be set exactly when the registration is RESERVED");
        }
    }

    public RegistrationId id() {
        return id;
    }

    public WorkshopId workshopId() {
        return workshopId;
    }

    public UserId userId() {
        return userId;
    }

    public TicketType ticketType() {
        return ticketType;
    }

    public RegistrationStatus status() {
        return status;
    }

    public boolean isConfirmed() {
        return status == RegistrationStatus.CONFIRMED;
    }

    /** Set exactly while {@code RESERVED}; {@code null} otherwise. */
    public Instant holdExpiresAt() {
        return holdExpiresAt;
    }

    public String ticketCode() {
        return ticketCode;
    }

    public String qrPayload() {
        return qrPayload;
    }

    public Version version() {
        return version;
    }
}
