package com.unihub.workshop.application.command;

import java.time.Instant;

public record SearchWorkshopsCriteria(
    String q, String speaker, String location, Instant dateFrom, Instant dateTo, int page, int size) {}
