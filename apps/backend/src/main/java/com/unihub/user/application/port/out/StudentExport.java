package com.unihub.user.application.port.out;

import java.util.stream.Stream;

/** One night's export file. It can be read more than once — the pipeline makes two passes. */
public interface StudentExport {

    /**
     * Opens a new pass over the file, header row included, in file order. The caller closes the
     * stream.
     *
     * @throws StudentExportUnreadableException while the stream is consumed, when the bytes cannot
     *     be read as UTF-8 or the file cannot be read at all
     */
    Stream<ExportLine> lines();
}
