package com.unihub.workshop.domain.exception;

import com.unihub.shared.domain.DomainException;
import com.unihub.shared.domain.ProblemKind;
import com.unihub.shared.domain.id.WorkshopId;

/**
 * The uploaded object is not an acceptable cover (Phase 8, D122): too big, empty, not the type the
 * URL was issued for, or its first bytes are not that format. HTTP 422. The object is deleted and
 * the pending key cleared before this is thrown, so a rejected file is never served and the next
 * attempt starts from a new URL.
 */
public class CoverImageRejectedException extends DomainException {
    public CoverImageRejectedException(WorkshopId id, String reason) {
        super("The cover image uploaded for workshop " + id.value() + " was rejected: " + reason);
    }

    @Override
    public ProblemKind kind() {
        return ProblemKind.UNPROCESSABLE;
    }

    @Override
    public String problemType() {
        return "urn:unihub:problem:cover-image-rejected";
    }
}
