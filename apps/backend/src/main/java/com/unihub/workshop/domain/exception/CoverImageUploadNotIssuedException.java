package com.unihub.workshop.domain.exception;

import com.unihub.shared.domain.DomainException;
import com.unihub.shared.domain.ProblemKind;
import com.unihub.shared.domain.id.WorkshopId;

/**
 * A confirmation for which there is nothing to confirm (workshop.md §7, Phase 8): no cover upload
 * URL was issued for this workshop, the one issued was already confirmed, or the object was
 * rejected and the key cleared. HTTP 409.
 */
public class CoverImageUploadNotIssuedException extends DomainException {
    public CoverImageUploadNotIssuedException(WorkshopId id) {
        super("No cover image upload is waiting to be confirmed for workshop " + id.value());
    }

    @Override
    public ProblemKind kind() {
        return ProblemKind.CONFLICT;
    }

    @Override
    public String problemType() {
        return "urn:unihub:problem:cover-image-upload-not-issued";
    }
}
