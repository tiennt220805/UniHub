package com.unihub.workshop.web.dto;

import com.unihub.shared.web.dto.PageMeta;
import java.util.List;

public record WorkshopListResponse(List<WorkshopSummary> items, PageMeta page) {}
