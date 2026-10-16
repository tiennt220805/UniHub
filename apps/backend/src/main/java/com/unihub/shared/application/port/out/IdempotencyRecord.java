package com.unihub.shared.application.port.out;

import java.util.Objects;

/** What the store knows about a key (backend-coding-standards.md §21). */
public sealed interface IdempotencyRecord {

    /** Fingerprint of the request body that first used the key. */
    String requestFingerprint();

    /** The key was taken and its operation has not finished (or crashed mid-way, G2). */
    record InProgress(String requestFingerprint) implements IdempotencyRecord {

        public InProgress {
            Objects.requireNonNull(requestFingerprint, "requestFingerprint");
        }
    }

    /** The operation finished with a business outcome, replayed verbatim on a retry. */
    record Completed(String requestFingerprint, StoredResult result) implements IdempotencyRecord {

        public Completed {
            Objects.requireNonNull(requestFingerprint, "requestFingerprint");
            Objects.requireNonNull(result, "result");
        }
    }
}
