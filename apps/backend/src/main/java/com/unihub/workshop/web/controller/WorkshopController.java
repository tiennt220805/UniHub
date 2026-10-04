package com.unihub.workshop.web.controller;

import com.unihub.workshop.application.port.in.SearchWorkshopsUseCase;
import com.unihub.workshop.web.dto.WorkshopListResponse;
import com.unihub.workshop.web.mapper.WorkshopWebMapper;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// GET /api/v1/workshops is public (packages/api-contract/paths/workshop.yaml: security: []) — no
// @PreAuthorize, no Spring Security dependency added (VIỆC 0.4: not wired in yet, Mục 2.2's job).
@RestController
@RequestMapping("/api/v1/workshops")
class WorkshopController {
    private final SearchWorkshopsUseCase searchWorkshops;

    WorkshopController(SearchWorkshopsUseCase searchWorkshops) {
        this.searchWorkshops = searchWorkshops;
    }

    @GetMapping
    WorkshopListResponse search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String speaker,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) Instant dateFrom,
            @RequestParam(required = false) Instant dateTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var criteria =
                WorkshopWebMapper.toCriteria(q, speaker, location, dateFrom, dateTo, page, size);
        var result = searchWorkshops.search(criteria);
        return WorkshopWebMapper.toResponse(result);
    }
}
