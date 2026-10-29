package com.unihub.notification.application.port.out;

import java.util.UUID;

/** Who to write to: the name to greet and the address. */
public record Recipient(UUID userId, String fullName, String email) {}
