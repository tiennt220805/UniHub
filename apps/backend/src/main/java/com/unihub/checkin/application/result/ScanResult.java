package com.unihub.checkin.application.result;

import com.unihub.checkin.domain.model.ScanId;
import com.unihub.checkin.domain.model.ScanOutcome;
import java.util.UUID;

/** The stored outcome of one scan at the moment the response is built. */
public record ScanResult(ScanId id, ScanOutcome outcome) {

    /** For the web layer, which may not name domain types. */
    public UUID scanId() {
        return id.value();
    }

    /** The outcome as the contract spells it. */
    public String outcomeName() {
        return outcome.name();
    }
}
