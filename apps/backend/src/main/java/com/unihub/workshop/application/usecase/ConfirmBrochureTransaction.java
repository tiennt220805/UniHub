package com.unihub.workshop.application.usecase;

import com.unihub.shared.application.port.out.AuditActions;
import com.unihub.shared.application.port.out.AuditEntry;
import com.unihub.shared.application.port.out.AuditTargets;
import com.unihub.shared.application.port.out.AuditTrail;
import com.unihub.shared.application.port.out.EventPublisher;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.security.Actor;
import com.unihub.workshop.application.event.BrochureSummarizationRequested;
import com.unihub.workshop.application.port.out.WorkshopBrochures;
import com.unihub.workshop.application.port.out.WorkshopCache;
import java.time.Clock;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one database transaction of a confirmation (workshop.md §7): the pending key becomes the
 * brochure and the summarization request is written to the outbox, together. A separate bean so
 * that the bucket call before it holds no transaction.
 */
@Component
class ConfirmBrochureTransaction {
    private final WorkshopBrochures brochures;
    private final EventPublisher eventPublisher;
    private final AuditTrail auditTrail;
    private final WorkshopCache cache;
    private final Clock clock;

    ConfirmBrochureTransaction(
            WorkshopBrochures brochures,
            EventPublisher eventPublisher,
            AuditTrail auditTrail,
            WorkshopCache cache,
            Clock clock) {
        this.brochures = brochures;
        this.eventPublisher = eventPublisher;
        this.auditTrail = auditTrail;
        this.cache = cache;
        this.clock = clock;
    }

    /**
     * @return false when the pending key is no longer {@code pendingKey} (another confirmation took
     *     it, or the Organizer asked for a new URL meanwhile); then nothing was written, and in
     *     particular no summarization request, so a paid AI call is never made twice for one key
     */
    @Transactional
    boolean persist(WorkshopId id, String pendingKey, Actor actor) {
        if (!brochures.confirm(id, pendingKey)) {
            return false;
        }
        var now = clock.instant();
        eventPublisher.publish(new BrochureSummarizationRequested(id.value(), pendingKey, now));
        // has_brochure flips: dropped after the commit, never before (WorkshopCache.invalidate).
        cache.invalidate(id);
        auditTrail.record(
                AuditEntry.of(
                        actor,
                        AuditActions.BROCHURE_UPLOAD_CONFIRMED,
                        AuditTargets.WORKSHOP,
                        id.value(),
                        now));
        return true;
    }
}
