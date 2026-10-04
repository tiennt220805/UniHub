package com.unihub.shared.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
        types = {"object"},
        requiredProperties = {"page", "size", "totalElements", "totalPages"})
public record PageMeta(int page, int size, long totalElements, int totalPages) {}
