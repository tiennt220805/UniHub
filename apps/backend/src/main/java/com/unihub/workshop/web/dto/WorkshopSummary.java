package com.unihub.workshop.web.dto;

import java.time.Instant;
import java.util.UUID;

public record WorkshopSummary(
        UUID id, String title, String location, Instant startTime, WorkshopStatus status) {}
