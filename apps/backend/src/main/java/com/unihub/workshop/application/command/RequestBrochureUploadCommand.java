package com.unihub.workshop.application.command;

import com.unihub.shared.security.Actor;
import java.util.UUID;

/** An Organizer asks for a URL to upload a brochure of this workshop to (workshop.md §7). */
public record RequestBrochureUploadCommand(UUID workshopId, Actor actor) {}
