package com.unihub.workshop.application.usecase;

import com.unihub.shared.application.port.out.AuditActions;
import com.unihub.shared.application.port.out.AuditEntry;
import com.unihub.shared.application.port.out.AuditTargets;
import com.unihub.shared.application.port.out.AuditTrail;
import com.unihub.shared.application.port.out.EventPublisher;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.application.command.CancelWorkshopCommand;
import com.unihub.workshop.application.event.WorkshopOutboxTranslator;
import com.unihub.workshop.application.port.in.CancelWorkshopUseCase;
import com.unihub.workshop.application.port.out.WorkshopCache;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import com.unihub.workshop.application.result.WorkshopResult;
import com.unihub.workshop.domain.exception.WorkshopNotFoundException;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CancelWorkshop implements CancelWorkshopUseCase {
    private final WorkshopRepository workshops;
    private final EventPublisher eventPublisher;
    private final AuditTrail auditTrail;
    private final WorkshopCache cache;
    private final Clock clock;

    CancelWorkshop(
            WorkshopRepository workshops,
            EventPublisher eventPublisher,
            AuditTrail auditTrail,
            WorkshopCache cache,
            Clock clock) {
        this.workshops = workshops;
        this.eventPublisher = eventPublisher;
        this.auditTrail = auditTrail;
        this.cache = cache;
        this.clock = clock;
    }

    @Override
    @Transactional
    public WorkshopResult cancel(CancelWorkshopCommand command) {
        var id = WorkshopId.of(command.workshopId());
        var workshop = workshops.findById(id).orElseThrow(() -> new WorkshopNotFoundException(id));
        var now = clock.instant();
        workshop.cancel(now);
        var saved = workshops.save(workshop);

        // Same transaction as the status change: the event exists exactly when the cancellation
        // does.
        workshop.pullEvents().stream()
                .flatMap(event -> WorkshopOutboxTranslator.translate(event).stream())
                .forEach(eventPublisher::publish);
        // Dropped after this transaction commits, never before (WorkshopCache.invalidate).
        cache.invalidate(id);
        auditTrail.record(
                AuditEntry.of(
                        command.actor(),
                        AuditActions.WORKSHOP_CANCELLED,
                        AuditTargets.WORKSHOP,
                        id.value(),
                        now));
        return WorkshopResult.from(saved);
    }
}
