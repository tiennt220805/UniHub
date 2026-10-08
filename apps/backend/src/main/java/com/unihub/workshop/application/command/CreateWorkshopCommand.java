package com.unihub.workshop.application.command;

import com.unihub.shared.security.Actor;
import java.math.BigDecimal;
import java.time.Instant;

public record CreateWorkshopCommand(
        Actor actor,
        String title,
        String description,
        String speakerName,
        String speakerBio,
        String location,
        Instant startTime,
        Instant endTime,
        int capacity,
        boolean paid,
        BigDecimal price) {}
