package com.unihub.workshop.web.dto;

import com.unihub.shared.web.dto.PageMeta;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(
        types = {"object"},
        requiredProperties = {"items", "page"})
public record WorkshopListResponse(List<WorkshopSummary> items, PageMeta page) {}
