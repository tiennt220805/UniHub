package com.unihub.checkin.web.dto;

import com.unihub.shared.web.dto.PageMeta;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** packages/api-contract {@code AttendanceListResponse}. */
@Schema(
        types = {"object"},
        requiredProperties = {"items", "page"})
public record AttendanceListResponse(List<AttendanceSummary> items, PageMeta page) {}
