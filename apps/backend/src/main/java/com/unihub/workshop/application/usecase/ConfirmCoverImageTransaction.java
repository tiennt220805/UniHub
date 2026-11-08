package com.unihub.workshop.application.usecase;

import com.unihub.shared.application.port.out.AuditActions;
import com.unihub.shared.application.port.out.AuditEntry;
import com.unihub.shared.application.port.out.AuditTargets;
import com.unihub.shared.application.port.out.AuditTrail;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.security.Actor;
import com.unihub.workshop.application.port.out.WorkshopCache;
import com.unihub.workshop.application.port.out.WorkshopCoverImages;
import java.time.Clock;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one database transaction of a cover confirmation (workshop.md §7): the pending key becomes
 * the cover. A separate bean so that the bucket calls before it hold no transaction.
 */
@Component
class ConfirmCoverImageTransaction {
    private final WorkshopCoverImages covers;
    private final AuditTrail auditTrail;
    private final WorkshopCache cache;
    private final Clock clock;

    ConfirmCoverImageTransaction(
            WorkshopCoverImages covers, AuditTrail auditTrail, WorkshopCache cache, Clock clock) {
        this.covers = covers;
        this.auditTrail = auditTrail;
        this.cache = cache;
        this.clock = clock;
    }

    /**
     * @return false when the pending key is no longer {@code pendingKey}; nothing was written
     */
    @Transactional
    boolean persist(WorkshopId id, String pendingKey, Actor actor) {
        if (!covers.confirm(id, pendingKey)) {
            return false;
        }
        // The cover URL is part of the cached detail: dropped after the commit, never before.
        cache.invalidate(id);
        auditTrail.record(
                AuditEntry.of(
                        actor,
                        AuditActions.COVER_IMAGE_UPLOAD_CONFIRMED,
                        AuditTargets.WORKSHOP,
                        id.value(),
                        clock.instant()));
        return true;
    }
}
