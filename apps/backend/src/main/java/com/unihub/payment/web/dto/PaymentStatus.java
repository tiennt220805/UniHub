package com.unihub.payment.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// enumAsRef: the contract declares PaymentStatus as a named component (schemas/payment.yaml);
// springdoc inlines enums unless told otherwise. All six values are possible from day one.
@Schema(enumAsRef = true)
public enum PaymentStatus {
    PENDING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    REFUND_REQUIRED,
    REFUNDED
}
