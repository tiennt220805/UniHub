package com.unihub.workshop.application.usecase;

import com.unihub.shared.application.port.out.AuditActions;
import com.unihub.shared.application.port.out.AuditEntry;
import com.unihub.shared.application.port.out.AuditTargets;
import com.unihub.shared.application.port.out.AuditTrail;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.application.command.PublishWorkshopCommand;
import com.unihub.workshop.application.port.in.PublishWorkshopUseCase;
import com.unihub.workshop.application.port.out.WorkshopCache;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import com.unihub.workshop.application.result.WorkshopResult;
import com.unihub.workshop.domain.exception.WorkshopNotFoundException;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DRAFT → PUBLISHED. No outbox message: messaging.md §2 has no routing key for a publish and no
 * module reacts to one. The cached entry must go, though — a cached DRAFT would keep answering 404
 * to students until it expires (workshop.md §2).
 */
@Service
class PublishWorkshop implements PublishWorkshopUseCase {
    private final WorkshopRepository workshops;
    private final AuditTrail auditTrail;
    private final WorkshopCache cache;
    private final Clock clock;

    PublishWorkshop(
            WorkshopRepository workshops, AuditTrail auditTrail, WorkshopCache cache, Clock clock) {
        this.workshops = workshops;
        this.auditTrail = auditTrail;
        this.cache = cache;
        this.clock = clock;
    }

    @Override
    @Transactional
    public WorkshopResult publish(PublishWorkshopCommand command) {
        var id = WorkshopId.of(command.workshopId());
        var workshop = workshops.findById(id).orElseThrow(() -> new WorkshopNotFoundException(id));
        workshop.publish();
        var saved = workshops.save(workshop);

        // Dropped after this transaction commits, never before (WorkshopCache.invalidate).
        cache.invalidate(id);
        auditTrail.record(
                AuditEntry.of(
                        command.actor(),
                        AuditActions.WORKSHOP_PUBLISHED,
                        AuditTargets.WORKSHOP,
                        id.value(),
                        clock.instant()));
        return WorkshopResult.from(saved);
    }
}
