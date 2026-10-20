package com.unihub.registration.domain.model;

import com.unihub.shared.domain.Version;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.domain.id.WorkshopId;
import java.time.Instant;

/**
 * Test Data Builder for {@link Registration} (test-data-management.md §1–§2). Baseline: a paid,
 * {@code RESERVED} registration holding its seat until 2026-12-01T09:15Z. The state is in the
 * factory name; it goes through {@link Registration#restore}, so an illegal combination fails
 * exactly as production code would.
 */
public final class RegistrationBuilder {
    public static final Instant HOLD_UNTIL = Instant.parse("2026-12-01T09:15:00Z");

    private RegistrationId id = RegistrationId.newId();
    private WorkshopId workshopId = WorkshopId.newId();
    private UserId userId = UserId.newId();
    private TicketType ticketType = TicketType.PAID;
    private RegistrationStatus status = RegistrationStatus.RESERVED;
    private Instant holdExpiresAt = HOLD_UNTIL;
    private Version version = Version.initial();

    private RegistrationBuilder() {}

    public static RegistrationBuilder aReservedRegistration() {
        return new RegistrationBuilder();
    }

    public static RegistrationBuilder aConfirmedRegistration() {
        return new RegistrationBuilder()
                .withStatus(RegistrationStatus.CONFIRMED)
                .withHoldExpiresAt(null);
    }

    public static RegistrationBuilder aCancelledRegistration() {
        return new RegistrationBuilder()
                .withStatus(RegistrationStatus.CANCELLED)
                .withHoldExpiresAt(null);
    }

    public static RegistrationBuilder anExpiredRegistration() {
        return new RegistrationBuilder()
                .withStatus(RegistrationStatus.EXPIRED)
                .withHoldExpiresAt(null);
    }

    public RegistrationBuilder withId(RegistrationId id) {
        this.id = id;
        return this;
    }

    public RegistrationBuilder withWorkshopId(WorkshopId workshopId) {
        this.workshopId = workshopId;
        return this;
    }

    public RegistrationBuilder withUserId(UserId userId) {
        this.userId = userId;
        return this;
    }

    public RegistrationBuilder withTicketType(TicketType ticketType) {
        this.ticketType = ticketType;
        return this;
    }

    public RegistrationBuilder withStatus(RegistrationStatus status) {
        this.status = status;
        return this;
    }

    public RegistrationBuilder withHoldExpiresAt(Instant holdExpiresAt) {
        this.holdExpiresAt = holdExpiresAt;
        return this;
    }

    public RegistrationBuilder withVersion(Version version) {
        this.version = version;
        return this;
    }

    public Registration build() {
        return Registration.restore(
                id, workshopId, userId, ticketType, status, holdExpiresAt, null, null, version);
    }
}
