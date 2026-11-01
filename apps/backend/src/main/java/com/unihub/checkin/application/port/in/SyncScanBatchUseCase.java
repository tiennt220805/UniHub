package com.unihub.checkin.application.port.in;

import com.unihub.checkin.application.command.SyncScanBatchCommand;
import com.unihub.checkin.application.result.SyncScanBatchResult;

public interface SyncScanBatchUseCase {

    /**
     * Records every scan of the batch and reconciles the registrations it touches
     * (earliest-timestamp-wins, checkin.md §7). Safe to send again after any failure: a scan is
     * identified by its own id.
     */
    SyncScanBatchResult sync(SyncScanBatchCommand command);
}
