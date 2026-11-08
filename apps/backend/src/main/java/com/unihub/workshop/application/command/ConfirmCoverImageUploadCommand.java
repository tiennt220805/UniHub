package com.unihub.workshop.application.command;

import com.unihub.shared.security.Actor;
import java.util.UUID;

/** The browser has put the cover image to the issued URL; the server checks what arrived. */
public record ConfirmCoverImageUploadCommand(UUID workshopId, Actor actor) {}
