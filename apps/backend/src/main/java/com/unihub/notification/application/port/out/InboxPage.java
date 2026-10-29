package com.unihub.notification.application.port.out;

import java.util.List;

public record InboxPage(
        List<InboxEntry> items, int page, int size, long totalElements, int totalPages) {}
