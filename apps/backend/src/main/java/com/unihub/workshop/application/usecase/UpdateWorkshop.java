package com.unihub.workshop.application.usecase;

import com.unihub.shared.application.port.out.AuditActions;
import com.unihub.shared.application.port.out.AuditEntry;
import com.unihub.shared.application.port.out.AuditTargets;
import com.unihub.shared.application.port.out.AuditTrail;
import com.unihub.shared.application.port.out.EventPublisher;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.application.command.UpdateWorkshopCommand;
import com.unihub.workshop.application.event.WorkshopOutboxTranslator;
import com.unihub.workshop.application.port.in.UpdateWorkshopUseCase;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import com.unihub.workshop.application.result.WorkshopResult;
import com.unihub.workshop.domain.event.WorkshopRescheduled;
import com.unihub.workshop.domain.exception.WorkshopNotFoundException;
import java.time.Clock;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class UpdateWorkshop implements UpdateWorkshopUseCase {
    private final WorkshopRepository workshops;
    private final EventPublisher eventPublisher;
    private final AuditTrail auditTrail;
    private final Clock clock;

    UpdateWorkshop(
            WorkshopRepository workshops,
            EventPublisher eventPublisher,
            AuditTrail auditTrail,
            Clock clock) {
        this.workshops = workshops;
        this.eventPublisher = eventPublisher;
        this.auditTrail = auditTrail;
        this.clock = clock;
    }

    @Override
    @Transactional
    public WorkshopResult update(UpdateWorkshopCommand command) {
        var id = WorkshopId.of(command.workshopId());
        var workshop = workshops.findById(id).orElseThrow(() -> new WorkshopNotFoundException(id));

        // Both steps check the status first, so a terminal workshop rejects even an empty update.
        // A failure in either rolls the whole transaction back: nothing is saved half-applied.
        workshop.revise(
                command.title().applyTo(workshop.title()),
                command.description().applyTo(workshop.description()),
                command.speakerName().applyTo(workshop.speakerName()),
                command.speakerBio().applyTo(workshop.speakerBio()),
                command.location().applyTo(workshop.location()),
                command.capacity().applyTo(workshop.capacity()),
                command.price().applyTo(workshop.price()));
        var now = clock.instant();
        workshop.reschedule(
                command.startTime().applyTo(workshop.startTime()),
                command.endTime().applyTo(workshop.endTime()),
                now);
        var saved = workshops.save(workshop);

        // Same transaction as the edit: a reschedule event exists exactly when the new times do.
        var events = workshop.pullEvents();
        events.stream()
                .flatMap(event -> WorkshopOutboxTranslator.translate(event).stream())
                .forEach(eventPublisher::publish);

        auditTrail.record(
                AuditEntry.of(
                        command.actor(),
                        AuditActions.WORKSHOP_UPDATED,
                        AuditTargets.WORKSHOP,
                        id.value(),
                        now));
        events.stream()
                .filter(WorkshopRescheduled.class::isInstance)
                .map(WorkshopRescheduled.class::cast)
                .forEach(
                        moved ->
                                auditTrail.record(
                                        AuditEntry.of(
                                                        command.actor(),
                                                        AuditActions.WORKSHOP_RESCHEDULED,
                                                        AuditTargets.WORKSHOP,
                                                        id.value(),
                                                        now)
                                                .withMetadata(
                                                        Map.of(
                                                                "previousStartTime",
                                                                moved.previousStartTime()
                                                                        .toString(),
                                                                "previousEndTime",
                                                                moved.previousEndTime().toString(),
                                                                "newStartTime",
                                                                moved.newStartTime().toString(),
                                                                "newEndTime",
                                                                moved.newEndTime().toString()))));
        return WorkshopResult.from(saved);
    }
}
