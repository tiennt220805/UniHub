package com.unihub.workshop.application.command;

/**
 * One field of a partial update: either left alone ({@link #unchanged()}) or set to a value — where
 * {@code null} is a real value, "clear this field". A plain nullable cannot tell "absent from the
 * request" from "explicitly null", and the contract's {@code PATCH} needs that difference.
 */
public record FieldChange<T>(boolean present, T value) {

    private static final FieldChange<?> UNCHANGED = new FieldChange<>(false, null);

    @SuppressWarnings("unchecked")
    public static <T> FieldChange<T> unchanged() {
        return (FieldChange<T>) UNCHANGED;
    }

    public static <T> FieldChange<T> to(T value) {
        return new FieldChange<>(true, value);
    }

    /** The new value if one was given, otherwise {@code current}. */
    public T applyTo(T current) {
        return present ? value : current;
    }
}
