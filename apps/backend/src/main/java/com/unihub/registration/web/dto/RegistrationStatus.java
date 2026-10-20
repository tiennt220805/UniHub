package com.unihub.registration.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// enumAsRef: the contract declares RegistrationStatus as a named component
// (schemas/registration.yaml); springdoc inlines enums unless told otherwise.
@Schema(enumAsRef = true)
public enum RegistrationStatus {
    RESERVED,
    CONFIRMED,
    CANCELLED,
    EXPIRED
}
