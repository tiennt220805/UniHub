package com.unihub.user.infrastructure.out.client;

import com.unihub.user.application.port.out.ExportLine;
import java.util.ArrayList;

/**
 * Splits one line of the export into fields: comma-separated, a field may be wrapped in double
 * quotes, and {@code ""} inside quotes is one literal quote (RFC 4180). Rows are one line each — a
 * quoted field cannot span a line break, so an opening quote left open is a malformed row.
 */
final class CsvLineTokenizer {

    private static final char SEPARATOR = ',';
    private static final char QUOTE = '"';

    private CsvLineTokenizer() {}

    static ExportLine tokenize(int lineNumber, String raw) {
        if (raw.indexOf('\u0000') >= 0) {
            return ExportLine.malformed(lineNumber, raw, "contains a NUL character");
        }
        var fields = new ArrayList<String>();
        var field = new StringBuilder();
        int i = 0;
        int length = raw.length();
        while (true) {
            if (i < length && raw.charAt(i) == QUOTE) {
                i++;
                boolean closed = false;
                while (i < length) {
                    char c = raw.charAt(i);
                    if (c != QUOTE) {
                        field.append(c);
                        i++;
                    } else if (i + 1 < length && raw.charAt(i + 1) == QUOTE) {
                        field.append(QUOTE);
                        i += 2;
                    } else {
                        i++;
                        closed = true;
                        break;
                    }
                }
                if (!closed) {
                    return ExportLine.malformed(lineNumber, raw, "unterminated quoted field");
                }
                if (i < length && raw.charAt(i) != SEPARATOR) {
                    return ExportLine.malformed(
                            lineNumber, raw, "text after a closing quote at position " + (i + 1));
                }
            } else {
                while (i < length && raw.charAt(i) != SEPARATOR) {
                    field.append(raw.charAt(i));
                    i++;
                }
            }
            fields.add(field.toString());
            field.setLength(0);
            if (i >= length) {
                return ExportLine.parsed(lineNumber, raw, fields);
            }
            i++;
        }
    }
}
