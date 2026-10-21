package com.unihub.registration.web.dto;

import com.unihub.shared.web.dto.PageMeta;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(
        types = {"object"},
        requiredProperties = {"items", "page"})
public record RegistrationListResponse(List<RegistrationSummary> items, PageMeta page) {}
