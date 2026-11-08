package com.unihub.workshop.application.command;

/**
 * What the management list asks for (Phase 8, D119). {@code status} is the name of a {@code
 * WorkshopStatus}, absent for all four; {@code titleContains} is already trimmed, absent when
 * blank.
 */
public record ListManagedWorkshopsCriteria(
        String status, String titleContains, int page, int size) {}
