package com.unihub.workshop.application.command;

import com.unihub.shared.security.Actor;
import java.util.UUID;

/**
 * An Organizer asks for a URL to upload a cover image of this workshop to: its declared type and
 * exact size in bytes, both of which the URL is signed for (workshop.md §7, Phase 8).
 */
public record RequestCoverImageUploadCommand(
        UUID workshopId, String contentType, long contentLength, Actor actor) {}
