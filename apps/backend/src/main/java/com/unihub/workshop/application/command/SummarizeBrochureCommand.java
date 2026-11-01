package com.unihub.workshop.application.command;

import java.util.UUID;

/** What the summarization message says: which workshop, and which brochure it was written for. */
public record SummarizeBrochureCommand(UUID workshopId, String brochureKey) {}
