package com.unihub.checkin.application.command;

import com.unihub.shared.security.Actor;
import java.util.List;
import java.util.Objects;

/** A batch of 1 … {@value #MAX_SCANS} scans uploaded by the staff member {@code actor}. */
public record SyncScanBatchCommand(Actor.UserActor actor, List<ScanSubmission> scans) {

    public static final int MAX_SCANS = 500;

    public SyncScanBatchCommand {
        Objects.requireNonNull(actor);
        scans = List.copyOf(scans);
        if (scans.isEmpty() || scans.size() > MAX_SCANS) {
            throw new IllegalArgumentException(
                    "a batch holds 1 to " + MAX_SCANS + " scans, got " + scans.size());
        }
    }
}
