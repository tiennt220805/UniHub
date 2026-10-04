package com.unihub.workshop.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// enumAsRef: the contract declares WorkshopStatus as a named component (schemas/workshop.yaml);
// springdoc inlines enums unless told otherwise.
@Schema(enumAsRef = true)
public enum WorkshopStatus {
    DRAFT,
    PUBLISHED,
    CANCELLED,
    COMPLETED
}
