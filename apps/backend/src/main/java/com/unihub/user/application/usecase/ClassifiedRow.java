package com.unihub.user.application.usecase;

import com.unihub.user.application.port.out.LegacyStudentRecord;
import com.unihub.user.application.port.out.QuarantinedRow;

/** A data row after Pass 2's parse + validate step (user.md §3): exactly one of two outcomes. */
sealed interface ClassifiedRow {

    /** Passed every check; still subject to the email-holder check at apply time. */
    record Valid(int rowNumber, String rawRow, LegacyStudentRecord student)
            implements ClassifiedRow {}

    record Rejected(QuarantinedRow row) implements ClassifiedRow {}
}
