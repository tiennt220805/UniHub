package com.unihub.workshop.web.mapper;

import com.unihub.shared.security.Actor;
import com.unihub.shared.web.dto.PageMeta;
import com.unihub.workshop.application.command.CancelWorkshopCommand;
import com.unihub.workshop.application.command.CreateWorkshopCommand;
import com.unihub.workshop.application.command.FieldChange;
import com.unihub.workshop.application.command.ListManagedWorkshopsCriteria;
import com.unihub.workshop.application.command.PublishWorkshopCommand;
import com.unihub.workshop.application.command.SearchWorkshopsCriteria;
import com.unihub.workshop.application.command.UpdateWorkshopCommand;
import com.unihub.workshop.application.result.SearchWorkshopsResult;
import com.unihub.workshop.application.result.WorkshopResult;
import com.unihub.workshop.web.dto.CreateWorkshopRequest;
import com.unihub.workshop.web.dto.UpdateWorkshopRequest;
import com.unihub.workshop.web.dto.Workshop;
import com.unihub.workshop.web.dto.WorkshopListResponse;
import com.unihub.workshop.web.dto.WorkshopStatus;
import com.unihub.workshop.web.dto.WorkshopSummary;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public final class WorkshopWebMapper {

    private WorkshopWebMapper() {}

    public static SearchWorkshopsCriteria toCriteria(
            String q,
            String speaker,
            String location,
            Instant dateFrom,
            Instant dateTo,
            int page,
            int size) {
        return new SearchWorkshopsCriteria(q, speaker, location, dateFrom, dateTo, page, size);
    }

    public static ListManagedWorkshopsCriteria toManagedCriteria(
            WorkshopStatus status, String q, int page, int size) {
        return new ListManagedWorkshopsCriteria(
                status == null ? null : status.name(), q, page, size);
    }

    public static WorkshopListResponse toResponse(SearchWorkshopsResult result) {
        List<WorkshopSummary> items =
                result.items().stream()
                        .map(
                                i ->
                                        new WorkshopSummary(
                                                i.id(),
                                                i.title(),
                                                i.location(),
                                                i.startTime(),
                                                WorkshopStatus.valueOf(i.status()),
                                                i.speakerName(),
                                                i.endTime(),
                                                i.capacity(),
                                                i.seatsTaken(),
                                                i.paid(),
                                                i.price(),
                                                i.coverImageUrl()))
                        .toList();
        PageMeta page =
                new PageMeta(
                        result.page(), result.size(), result.totalElements(), result.totalPages());
        return new WorkshopListResponse(items, page);
    }

    public static CreateWorkshopCommand toCommand(CreateWorkshopRequest r, Actor actor) {
        return new CreateWorkshopCommand(
                actor,
                r.title(),
                r.description(),
                r.speakerName(),
                r.speakerBio(),
                r.location(),
                r.startTime(),
                r.endTime(),
                r.capacity(),
                r.isPaid(),
                r.price());
    }

    public static UpdateWorkshopCommand toCommand(UUID id, UpdateWorkshopRequest r, Actor actor) {
        return new UpdateWorkshopCommand(
                actor,
                id,
                change(r.hasTitle(), r::getTitle),
                change(r.hasDescription(), r::getDescription),
                change(r.hasSpeakerName(), r::getSpeakerName),
                change(r.hasSpeakerBio(), r::getSpeakerBio),
                change(r.hasLocation(), r::getLocation),
                change(r.hasStartTime(), r::getStartTime),
                change(r.hasEndTime(), r::getEndTime),
                change(r.hasCapacity(), r::getCapacity),
                change(r.hasPrice(), r::getPrice));
    }

    public static PublishWorkshopCommand toPublishCommand(UUID id, Actor actor) {
        return new PublishWorkshopCommand(actor, id);
    }

    public static CancelWorkshopCommand toCancelCommand(UUID id, Actor actor) {
        return new CancelWorkshopCommand(actor, id);
    }

    public static Workshop toResponse(WorkshopResult r) {
        return new Workshop(
                r.id(),
                r.title(),
                r.description(),
                r.aiSummary(),
                r.speakerName(),
                r.speakerBio(),
                r.location(),
                r.startTime(),
                r.endTime(),
                r.capacity(),
                r.seatsTaken(),
                r.paid(),
                r.price(),
                r.hasBrochure(),
                r.coverImageUrl(),
                WorkshopStatus.valueOf(r.status()),
                Math.toIntExact(r.version()), // workshops.version is an INT column
                r.createdAt(),
                r.updatedAt());
    }

    private static <T> FieldChange<T> change(boolean present, Supplier<T> value) {
        return present ? FieldChange.to(value.get()) : FieldChange.unchanged();
    }
}
