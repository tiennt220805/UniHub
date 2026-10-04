package com.unihub.workshop.application.port.out;

import java.util.List;

public record WorkshopCatalogPage(
        List<WorkshopCatalogEntry> items, int page, int size, long totalElements, int totalPages) {}
