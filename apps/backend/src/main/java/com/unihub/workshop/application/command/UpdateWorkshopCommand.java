package com.unihub.workshop.application.command;

import com.unihub.shared.security.Actor;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Partial update (edit and reschedule are the same operation, packages/api-contract {@code
 * updateWorkshop}): each field is a {@link FieldChange}, so only what the request carried changes.
 * {@code isPaid} is deliberately absent — a workshop cannot flip between free and paid.
 */
public record UpdateWorkshopCommand(
        Actor actor,
        UUID workshopId,
        FieldChange<String> title,
        FieldChange<String> description,
        FieldChange<String> speakerName,
        FieldChange<String> speakerBio,
        FieldChange<String> location,
        FieldChange<Instant> startTime,
        FieldChange<Instant> endTime,
        FieldChange<Integer> capacity,
        FieldChange<BigDecimal> price) {

    /** A command that changes nothing until its setters are called. */
    public static Builder builder(Actor actor, UUID workshopId) {
        return new Builder(actor, workshopId);
    }

    public static final class Builder {
        private final Actor actor;
        private final UUID workshopId;
        private FieldChange<String> title = FieldChange.unchanged();
        private FieldChange<String> description = FieldChange.unchanged();
        private FieldChange<String> speakerName = FieldChange.unchanged();
        private FieldChange<String> speakerBio = FieldChange.unchanged();
        private FieldChange<String> location = FieldChange.unchanged();
        private FieldChange<Instant> startTime = FieldChange.unchanged();
        private FieldChange<Instant> endTime = FieldChange.unchanged();
        private FieldChange<Integer> capacity = FieldChange.unchanged();
        private FieldChange<BigDecimal> price = FieldChange.unchanged();

        private Builder(Actor actor, UUID workshopId) {
            this.actor = actor;
            this.workshopId = workshopId;
        }

        public Builder title(FieldChange<String> title) {
            this.title = title;
            return this;
        }

        public Builder description(FieldChange<String> description) {
            this.description = description;
            return this;
        }

        public Builder speakerName(FieldChange<String> speakerName) {
            this.speakerName = speakerName;
            return this;
        }

        public Builder speakerBio(FieldChange<String> speakerBio) {
            this.speakerBio = speakerBio;
            return this;
        }

        public Builder location(FieldChange<String> location) {
            this.location = location;
            return this;
        }

        public Builder startTime(FieldChange<Instant> startTime) {
            this.startTime = startTime;
            return this;
        }

        public Builder endTime(FieldChange<Instant> endTime) {
            this.endTime = endTime;
            return this;
        }

        public Builder capacity(FieldChange<Integer> capacity) {
            this.capacity = capacity;
            return this;
        }

        public Builder price(FieldChange<BigDecimal> price) {
            this.price = price;
            return this;
        }

        public UpdateWorkshopCommand build() {
            return new UpdateWorkshopCommand(
                    actor,
                    workshopId,
                    title,
                    description,
                    speakerName,
                    speakerBio,
                    location,
                    startTime,
                    endTime,
                    capacity,
                    price);
        }
    }
}
