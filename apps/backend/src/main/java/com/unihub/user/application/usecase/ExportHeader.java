package com.unihub.user.application.usecase;

import com.unihub.user.application.port.out.ExportLine;
import com.unihub.user.application.port.out.StudentExportUnreadableException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The export's header row: where each required column sits and how many columns every row must have
 * (user.md §4, MALFORMED_ROW). A header that cannot be used makes the whole file unreadable
 * (user.md §2), never a per-row rejection.
 */
final class ExportHeader {

    static final String LEGACY_STUDENT_ID = "legacy_student_id";
    static final String EMAIL = "email";
    static final String FULL_NAME = "full_name";

    private static final char BYTE_ORDER_MARK = '﻿';

    private final int columnCount;
    private final int legacyStudentIdIndex;
    private final int emailIndex;
    private final int fullNameIndex;

    private ExportHeader(
            int columnCount, int legacyStudentIdIndex, int emailIndex, int fullNameIndex) {
        this.columnCount = columnCount;
        this.legacyStudentIdIndex = legacyStudentIdIndex;
        this.emailIndex = emailIndex;
        this.fullNameIndex = fullNameIndex;
    }

    /**
     * Reads the header row. Column names are matched trimmed and case-insensitively; extra columns
     * are allowed and ignored.
     *
     * @throws StudentExportUnreadableException when the row cannot be parsed, repeats a column, or
     *     lacks a required one
     */
    static ExportHeader parse(ExportLine line) {
        if (line.malformation().isPresent()) {
            throw unreadable("header row is malformed: " + line.malformation().get());
        }
        var indexes = new HashMap<String, Integer>();
        List<String> fields = line.fields();
        for (int i = 0; i < fields.size(); i++) {
            var name = fields.get(i);
            if (i == 0 && !name.isEmpty() && name.charAt(0) == BYTE_ORDER_MARK) {
                name = name.substring(1);
            }
            name = name.trim().toLowerCase(Locale.ROOT);
            if (indexes.putIfAbsent(name, i) != null) {
                throw unreadable("header repeats column '" + name + "'");
            }
        }
        return new ExportHeader(
                fields.size(),
                required(indexes, LEGACY_STUDENT_ID),
                required(indexes, EMAIL),
                required(indexes, FULL_NAME));
    }

    private static int required(HashMap<String, Integer> indexes, String column) {
        var index = indexes.get(column);
        if (index == null) {
            throw unreadable("header lacks required column '" + column + "'");
        }
        return index;
    }

    private static StudentExportUnreadableException unreadable(String message) {
        return new StudentExportUnreadableException(message, null);
    }

    /** Why the row does not fit this header, or empty when its columns line up. */
    Optional<String> misfit(ExportLine line) {
        if (line.malformation().isPresent()) {
            return line.malformation();
        }
        if (line.fields().size() != columnCount) {
            return Optional.of(
                    "expected " + columnCount + " columns, found " + line.fields().size());
        }
        return Optional.empty();
    }

    /** The trimmed value; call only on a row {@link #misfit} accepted. */
    String legacyStudentId(ExportLine line) {
        return line.fields().get(legacyStudentIdIndex).trim();
    }

    String email(ExportLine line) {
        return line.fields().get(emailIndex).trim();
    }

    String fullName(ExportLine line) {
        return line.fields().get(fullNameIndex).trim();
    }
}
