package com.unihub.workshop.web.mapper;

import com.unihub.shared.web.dto.PageMeta;
import com.unihub.workshop.application.command.SearchWorkshopsCriteria;
import com.unihub.workshop.application.result.SearchWorkshopsResult;
import com.unihub.workshop.web.dto.WorkshopListResponse;
import com.unihub.workshop.web.dto.WorkshopStatus;
import com.unihub.workshop.web.dto.WorkshopSummary;
import java.time.Instant;
import java.util.List;

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
                        WorkshopStatus.valueOf(i.status())))
            .toList();
    PageMeta page = new PageMeta(result.page(), result.size(), result.totalElements(), result.totalPages());
    return new WorkshopListResponse(items, page);
  }
}
