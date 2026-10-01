package com.unihub.workshop.application.port.out;

import java.time.Instant;
import java.util.UUID;

public record WorkshopCatalogEntry(
    UUID id, String title, String location, Instant startTime, String status) {}
