package com.unihub.user.application.port.out;

import java.util.List;
import java.util.Optional;

/**
 * One physical line of the export, split into CSV fields.
 *
 * @param lineNumber 1-based line in the file; the header is line 1
 * @param raw the line exactly as read, without its line terminator
 * @param fields the parsed fields; empty when {@code malformation} is present
 * @param malformation why the line could not be split into fields (e.g. an unterminated quote)
 */
public record ExportLine(
        int lineNumber, String raw, List<String> fields, Optional<String> malformation) {

    public ExportLine {
        fields = List.copyOf(fields);
    }

    public static ExportLine parsed(int lineNumber, String raw, List<String> fields) {
        return new ExportLine(lineNumber, raw, fields, Optional.empty());
    }

    public static ExportLine malformed(int lineNumber, String raw, String malformation) {
        return new ExportLine(lineNumber, raw, List.of(), Optional.of(malformation));
    }
}
