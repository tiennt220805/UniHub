package com.unihub.checkin.web.mapper;

import com.unihub.checkin.application.command.ScanSubmission;
import com.unihub.checkin.application.command.SyncScanBatchCommand;
import com.unihub.checkin.application.result.ScanResult;
import com.unihub.checkin.application.result.SyncScanBatchResult;
import com.unihub.checkin.application.result.VerificationKeyResult;
import com.unihub.checkin.web.dto.CheckInScanOutcome;
import com.unihub.checkin.web.dto.DeviceVerdict;
import com.unihub.checkin.web.dto.ScanEvent;
import com.unihub.checkin.web.dto.ScanSyncResult;
import com.unihub.checkin.web.dto.SyncScanBatchRequest;
import com.unihub.checkin.web.dto.SyncScanBatchResponse;
import com.unihub.checkin.web.dto.VerificationKeyResponse;
import com.unihub.shared.security.Actor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class CheckInWebMapper {

    /** {@code registrations.ticket_code} is {@code VARCHAR(64)}. */
    private static final int MAX_TICKET_CODE = 64;

    private CheckInWebMapper() {}

    /**
     * The code of a scan the device rejected is never read, whatever JSON value it is: it is
     * attacker-controlled, and a value that failed validation would fail the whole batch and block
     * every later sync of the device (Phase 5 checklist D50). The code of a verified scan must be a
     * string of 1 to 64 characters, or the batch is a 400.
     */
    public static SyncScanBatchCommand toCommand(
            SyncScanBatchRequest request, Actor.UserActor actor) {
        return new SyncScanBatchCommand(
                actor, request.scans().stream().map(CheckInWebMapper::toSubmission).toList());
    }

    private static ScanSubmission toSubmission(ScanEvent scan) {
        String code = null;
        if (scan.deviceVerdict() == DeviceVerdict.VERIFIED) {
            var node = scan.ticketCode();
            if (node == null || !node.isTextual() || node.textValue().isBlank()) {
                throw badRequest("A VERIFIED scan needs a ticketCode of 1 to 64 characters.");
            }
            code = node.textValue();
            if (code.length() > MAX_TICKET_CODE) {
                throw badRequest("A VERIFIED scan needs a ticketCode of 1 to 64 characters.");
            }
        }
        return ScanSubmission.of(
                scan.id(),
                scan.deviceVerdict().name(),
                code,
                scan.workshopId(),
                scan.scannedAt(),
                scan.deviceId());
    }

    public static SyncScanBatchResponse toResponse(SyncScanBatchResult result) {
        return new SyncScanBatchResponse(
                result.results().stream().map(CheckInWebMapper::toResult).toList());
    }

    public static VerificationKeyResponse toResponse(VerificationKeyResult key) {
        return new VerificationKeyResponse(key.kty(), key.crv(), key.x(), key.y());
    }

    private static ScanSyncResult toResult(ScanResult result) {
        return new ScanSyncResult(
                result.scanId(), CheckInScanOutcome.valueOf(result.outcomeName()));
    }

    private static ResponseStatusException badRequest(String detail) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
    }
}
