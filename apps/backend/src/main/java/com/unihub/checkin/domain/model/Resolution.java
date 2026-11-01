package com.unihub.checkin.domain.model;

import java.util.Map;

/**
 * The result of reconciling one registration's valid scans: which scan counts, and the outcome of
 * every scan in the set. Never contains {@code REJECTED_INVALID}; a scan judged invalid is not part
 * of the set.
 */
public record Resolution(ScanId winner, Map<ScanId, ScanOutcome> outcomes) {

    public Resolution {
        outcomes = Map.copyOf(outcomes);
    }
}
