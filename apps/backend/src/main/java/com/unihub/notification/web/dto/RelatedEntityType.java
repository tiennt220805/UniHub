package com.unihub.notification.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(enumAsRef = true)
public enum RelatedEntityType {
    WORKSHOP,
    REGISTRATION
}
