package com.unihub.checkin.application.port.out;

import java.util.List;

public record AttendancePage(
        List<AttendanceEntry> items, int page, int size, long totalElements, int totalPages) {}
