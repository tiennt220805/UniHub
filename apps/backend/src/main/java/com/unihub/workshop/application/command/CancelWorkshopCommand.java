package com.unihub.workshop.application.command;

import com.unihub.shared.security.Actor;
import java.util.UUID;

public record CancelWorkshopCommand(Actor actor, UUID workshopId) {}
