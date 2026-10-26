package com.unihub.user.application.port.out;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The {@code users} rows the nightly sync writes (user.md §8). */
public interface LegacyStudentAccounts {

    /**
     * Who already holds each of these emails: email → that account's {@code legacy_student_id},
     * empty for a directly-provisioned account. Emails nobody holds are absent from the map.
     */
    Map<String, Optional<String>> holdersOfEmails(Collection<String> emails);

    /**
     * Inserts or refreshes each student by {@code legacy_student_id}. Never touches {@code
     * password_hash} or {@code status}; safe to apply again, or concurrently, with the same input.
     * The records' legacy ids and emails must each be distinct. When a concurrent run has just
     * written a student, the refusal of the e-mail index is absorbed by retrying that one student
     * once (user.md §8); an e-mail held by another account still fails the call.
     */
    UpsertOutcome upsert(List<LegacyStudentRecord> students);

    /** How many rows were inserted, and how many existing rows actually changed. */
    record UpsertOutcome(int created, int updated) {}
}
