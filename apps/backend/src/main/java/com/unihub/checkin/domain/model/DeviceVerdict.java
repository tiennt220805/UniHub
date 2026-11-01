package com.unihub.checkin.domain.model;

/** What the device's own offline signature check said about the QR (checkin.md §6, §7). */
public enum DeviceVerdict {
    VERIFIED,
    REJECTED_SIGNATURE
}
