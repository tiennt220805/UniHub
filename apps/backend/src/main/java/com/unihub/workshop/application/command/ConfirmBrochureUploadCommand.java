package com.unihub.workshop.application.command;

import com.unihub.shared.security.Actor;
import java.util.UUID;

/** The Organizer's browser says the upload to the issued URL is done (workshop.md §7). */
public record ConfirmBrochureUploadCommand(UUID workshopId, Actor actor) {}
