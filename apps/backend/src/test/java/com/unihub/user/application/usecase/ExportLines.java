package com.unihub.user.application.usecase;

import com.unihub.user.application.port.out.ExportLine;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Builds export lines for use case tests. Plain comma split — no quoting needed here. */
final class ExportLines {

    static final String HEADER = "legacy_student_id,email,full_name";

    private ExportLines() {}

    /** Numbers the given lines from 1 (the header) in order. */
    static List<ExportLine> numbered(String... raws) {
        var lines = new ArrayList<ExportLine>();
        for (int i = 0; i < raws.length; i++) {
            lines.add(ExportLine.parsed(i + 1, raws[i], Arrays.asList(raws[i].split(",", -1))));
        }
        return lines;
    }

    static ExportLine line(int lineNumber, String raw) {
        return ExportLine.parsed(lineNumber, raw, Arrays.asList(raw.split(",", -1)));
    }
}
