package com.unihub.user.application.usecase;

import com.unihub.user.application.port.out.ExportLine;
import com.unihub.user.application.port.out.LegacyStudentRecord;
import com.unihub.user.application.port.out.QuarantineReason;
import com.unihub.user.application.port.out.QuarantinedRow;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * user.md §3–§4. {@link #scan} is Pass 1: it counts every {@code legacy_student_id} and {@code
 * email} in the file without touching the database. {@link #classify} is Pass 2's parse + validate
 * step, so a repeated key is rejected on every row carrying it, the first one included.
 */
final class ExportRowClassifier {

    /** Column widths of {@code users} (database-schema.md §2.1). */
    static final int LEGACY_STUDENT_ID_MAX = 50;

    static final int EMAIL_MAX = 255;
    static final int FULL_NAME_MAX = 255;

    /** RFC 5322-shaped: a local part, an {@code @}, and a dotted domain — not full RFC parsing. */
    private static final Pattern EMAIL_SHAPE =
            Pattern.compile(
                    "[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+"
                            + "@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"
                            + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+");

    private final ExportHeader header;
    private final Map<String, Integer> legacyStudentIdCounts;
    private final Map<String, Integer> emailCounts;

    private ExportRowClassifier(
            ExportHeader header,
            Map<String, Integer> legacyStudentIdCounts,
            Map<String, Integer> emailCounts) {
        this.header = header;
        this.legacyStudentIdCounts = legacyStudentIdCounts;
        this.emailCounts = emailCounts;
    }

    /**
     * Pass 1 over the data rows (header already consumed). Only rows whose columns line up are
     * counted: a malformed row's fields cannot be trusted to sit in the right columns.
     */
    static ExportRowClassifier scan(ExportHeader header, Iterator<ExportLine> dataLines) {
        var legacyStudentIds = new HashMap<String, Integer>();
        var emails = new HashMap<String, Integer>();
        while (dataLines.hasNext()) {
            var line = dataLines.next();
            if (header.misfit(line).isPresent()) {
                continue;
            }
            countIfPresent(legacyStudentIds, header.legacyStudentId(line));
            countIfPresent(emails, header.email(line));
        }
        return new ExportRowClassifier(header, legacyStudentIds, emails);
    }

    private static void countIfPresent(Map<String, Integer> counts, String value) {
        if (!value.isEmpty()) {
            counts.merge(value, 1, Integer::sum);
        }
    }

    ClassifiedRow classify(ExportLine line) {
        var misfit = header.misfit(line);
        if (misfit.isPresent()) {
            return rejected(line, QuarantineReason.MALFORMED_ROW, misfit.get());
        }
        var legacyStudentId = header.legacyStudentId(line);
        var email = header.email(line);
        var fullName = header.fullName(line);

        if (legacyStudentId.isEmpty()) {
            return invalid(line, "legacy_student_id is empty");
        }
        if (legacyStudentId.length() > LEGACY_STUDENT_ID_MAX) {
            return invalid(line, "legacy_student_id is longer than " + LEGACY_STUDENT_ID_MAX);
        }
        if (email.isEmpty()) {
            return invalid(line, "email is empty");
        }
        if (email.length() > EMAIL_MAX) {
            return invalid(line, "email is longer than " + EMAIL_MAX);
        }
        if (!EMAIL_SHAPE.matcher(email).matches()) {
            return invalid(line, "email is not a valid address");
        }
        if (fullName.isEmpty()) {
            return invalid(line, "full_name is empty");
        }
        if (fullName.length() > FULL_NAME_MAX) {
            return invalid(line, "full_name is longer than " + FULL_NAME_MAX);
        }

        if (legacyStudentIdCounts.getOrDefault(legacyStudentId, 0) > 1) {
            return rejected(
                    line,
                    QuarantineReason.DUPLICATE_IN_FILE,
                    "legacy_student_id appears "
                            + legacyStudentIdCounts.get(legacyStudentId)
                            + " times in the file");
        }
        if (emailCounts.getOrDefault(email, 0) > 1) {
            return rejected(
                    line,
                    QuarantineReason.DUPLICATE_IN_FILE,
                    "email appears " + emailCounts.get(email) + " times in the file");
        }
        return new ClassifiedRow.Valid(
                line.lineNumber(),
                line.raw(),
                new LegacyStudentRecord(legacyStudentId, email, fullName));
    }

    private static ClassifiedRow invalid(ExportLine line, String detail) {
        return rejected(line, QuarantineReason.INVALID_FIELD, detail);
    }

    private static ClassifiedRow rejected(ExportLine line, QuarantineReason reason, String detail) {
        return new ClassifiedRow.Rejected(
                new QuarantinedRow(line.lineNumber(), line.raw(), reason, detail));
    }
}
