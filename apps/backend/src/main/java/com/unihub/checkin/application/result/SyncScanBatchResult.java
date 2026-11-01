package com.unihub.checkin.application.result;

import java.util.List;

/** One result per distinct scan id of the batch, in the order the ids first appeared. */
public record SyncScanBatchResult(List<ScanResult> results) {

    public SyncScanBatchResult {
        results = List.copyOf(results);
    }
}
