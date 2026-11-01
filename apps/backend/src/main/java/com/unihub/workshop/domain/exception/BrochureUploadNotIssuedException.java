package com.unihub.workshop.domain.exception;

import com.unihub.shared.domain.DomainException;
import com.unihub.shared.domain.ProblemKind;
import com.unihub.shared.domain.id.WorkshopId;

/**
 * A confirmation for which there is nothing to confirm (workshop.md §7): no upload URL was issued
 * for this workshop, or the one issued was already confirmed (also what the loser of two concurrent
 * confirmations gets). HTTP 409; no outbox row is written.
 */
public class BrochureUploadNotIssuedException extends DomainException {

    public BrochureUploadNotIssuedException(WorkshopId id) {
        super("No brochure upload is waiting to be confirmed for workshop " + id.value());
    }

    @Override
    public ProblemKind kind() {
        return ProblemKind.CONFLICT;
    }

    @Override
    public String problemType() {
        return "urn:unihub:problem:brochure-upload-not-issued";
    }
}
