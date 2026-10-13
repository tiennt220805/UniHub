package com.unihub.user.application.port.out;

/**
 * A rejected export row.
 *
 * @param rowNumber the row's line number in the file (the header is line 1)
 * @param rawRow the line exactly as the legacy system sent it
 */
public record QuarantinedRow(
        int rowNumber, String rawRow, QuarantineReason reason, String reasonDetail) {}
