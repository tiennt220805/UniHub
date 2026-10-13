package com.unihub.user.application.port.out;

/** A validated export row, ready for the upsert. */
public record LegacyStudentRecord(String legacyStudentId, String email, String fullName) {}
