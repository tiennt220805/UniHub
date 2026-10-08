package com.unihub.workshop.web.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * packages/api-contract {@code UpdateWorkshopRequest}: every field optional, only the ones present
 * change. A {@code PATCH} must tell "field absent" (leave it) from "field is null" (clear it),
 * which a plain Java field cannot, so each setter records that it was called. The {@code has…}
 * methods and the {@code @AssertTrue} rules are hidden from JSON and from the generated schema,
 * which stays identical to the contract.
 */
@Schema(types = {"object"})
// ignored.
public class UpdateWorkshopRequest {

    private String title;
    private String description;
    private String speakerName;
    private String speakerBio;
    private String location;
    private Instant startTime;
    private Instant endTime;
    private Integer capacity;
    private BigDecimal price;

    private boolean titleSet;
    private boolean descriptionSet;
    private boolean speakerNameSet;
    private boolean speakerBioSet;
    private boolean locationSet;
    private boolean startTimeSet;
    private boolean endTimeSet;
    private boolean capacitySet;
    private boolean priceSet;

    @Size(max = 255)
    public String getTitle() {
        return title;
    }

    @JsonSetter("title")
    public void setTitle(String title) {
        this.title = title;
        this.titleSet = true;
    }

    @Schema(types = {"string", "null"})
    public String getDescription() {
        return description;
    }

    @JsonSetter("description")
    public void setDescription(String description) {
        this.description = description;
        this.descriptionSet = true;
    }

    @Size(max = 255)
    public String getSpeakerName() {
        return speakerName;
    }

    @JsonSetter("speakerName")
    public void setSpeakerName(String speakerName) {
        this.speakerName = speakerName;
        this.speakerNameSet = true;
    }

    @Schema(types = {"string", "null"})
    public String getSpeakerBio() {
        return speakerBio;
    }

    @JsonSetter("speakerBio")
    public void setSpeakerBio(String speakerBio) {
        this.speakerBio = speakerBio;
        this.speakerBioSet = true;
    }

    @Size(max = 255)
    public String getLocation() {
        return location;
    }

    @JsonSetter("location")
    public void setLocation(String location) {
        this.location = location;
        this.locationSet = true;
    }

    public Instant getStartTime() {
        return startTime;
    }

    @JsonSetter("startTime")
    public void setStartTime(Instant startTime) {
        this.startTime = startTime;
        this.startTimeSet = true;
    }

    public Instant getEndTime() {
        return endTime;
    }

    @JsonSetter("endTime")
    public void setEndTime(Instant endTime) {
        this.endTime = endTime;
        this.endTimeSet = true;
    }

    @Min(0)
    public Integer getCapacity() {
        return capacity;
    }

    @JsonSetter("capacity")
    public void setCapacity(Integer capacity) {
        this.capacity = capacity;
        this.capacitySet = true;
    }

    @Schema(
            types = {"number", "null"},
            format = "double")
    public BigDecimal getPrice() {
        return price;
    }

    @JsonSetter("price")
    public void setPrice(BigDecimal price) {
        this.price = price;
        this.priceSet = true;
    }

    // A field the PATCH cannot change (isPaid, seatsTaken, status, ...) is a 400, never silently
    // ignored: Jackson would otherwise drop it and the client would believe it took effect.
    @JsonAnySetter
    void rejectUnknownField(String name, Object ignored) {
        throw new IllegalArgumentException("unknown or read-only field: " + name);
    }

    public boolean hasTitle() {
        return titleSet;
    }

    public boolean hasDescription() {
        return descriptionSet;
    }

    public boolean hasSpeakerName() {
        return speakerNameSet;
    }

    public boolean hasSpeakerBio() {
        return speakerBioSet;
    }

    public boolean hasLocation() {
        return locationSet;
    }

    public boolean hasStartTime() {
        return startTimeSet;
    }

    public boolean hasEndTime() {
        return endTimeSet;
    }

    public boolean hasCapacity() {
        return capacitySet;
    }

    public boolean hasPrice() {
        return priceSet;
    }

    // title, speakerName, location, capacity, startTime and endTime are not nullable in the
    // contract: sending one as null is a malformed request, not a request to clear it.
    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "title, speakerName and location, when sent, must not be blank or null")
    public boolean isRequiredTextNotBlank() {
        return (!titleSet || notBlank(title))
                && (!speakerNameSet || notBlank(speakerName))
                && (!locationSet || notBlank(location));
    }

    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "capacity, startTime and endTime, when sent, must not be null")
    public boolean isRequiredValuesNotNull() {
        return (!capacitySet || capacity != null)
                && (!startTimeSet || startTime != null)
                && (!endTimeSet || endTime != null);
    }

    // Only checkable when both are in the request; a lone startTime or endTime is checked against
    // the stored other one by the domain.
    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "endTime must be after startTime")
    public boolean isEndAfterStart() {
        return startTime == null || endTime == null || endTime.isAfter(startTime);
    }

    // Whether a price is allowed at all depends on the stored isPaid (domain); that it is positive
    // does not.
    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "price, when sent, must be positive")
    public boolean isPricePositive() {
        return price == null || price.signum() > 0;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
