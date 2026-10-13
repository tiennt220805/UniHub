package com.unihub.user.infrastructure.out.client;

import com.unihub.user.application.port.out.ExportLine;
import com.unihub.user.application.port.out.StudentExport;
import com.unihub.user.application.port.out.StudentExportSource;
import com.unihub.user.application.port.out.StudentExportUnreadableException;
import com.unihub.user.infrastructure.config.NightlySyncProperties;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.springframework.stereotype.Component;

/**
 * Reads {@code students_export_YYYYMMDD.csv} from the shared inbound volume (user.md §2) as strict
 * UTF-8: a byte sequence that is not UTF-8 makes the file unreadable instead of being silently
 * replaced.
 */
@Component
class FileSystemStudentExportSource implements StudentExportSource {

    private final Path inboundDir;

    FileSystemStudentExportSource(NightlySyncProperties properties) {
        this.inboundDir = properties.inboundDir();
    }

    static String fileName(LocalDate exportDate) {
        return "students_export_" + exportDate.format(DateTimeFormatter.BASIC_ISO_DATE) + ".csv";
    }

    @Override
    public Optional<StudentExport> find(LocalDate exportDate) {
        var file = inboundDir.resolve(fileName(exportDate));
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return Optional.of(() -> lines(file));
    }

    private static Stream<ExportLine> lines(Path file) {
        BufferedReader reader;
        try {
            reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new StudentExportUnreadableException("cannot open " + file.getFileName(), e);
        }
        return StreamSupport.stream(
                        Spliterators.spliteratorUnknownSize(
                                new LineIterator(reader),
                                Spliterator.ORDERED | Spliterator.NONNULL),
                        false)
                .onClose(() -> close(reader));
    }

    private static void close(BufferedReader reader) {
        try {
            reader.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final class LineIterator implements Iterator<ExportLine> {

        private final BufferedReader reader;
        private int lineNumber;
        private String pending;
        private boolean exhausted;

        private LineIterator(BufferedReader reader) {
            this.reader = reader;
        }

        @Override
        public boolean hasNext() {
            if (pending == null && !exhausted) {
                pending = readLine();
                exhausted = pending == null;
            }
            return pending != null;
        }

        @Override
        public ExportLine next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            var raw = pending;
            pending = null;
            lineNumber++;
            return CsvLineTokenizer.tokenize(lineNumber, raw);
        }

        private String readLine() {
            try {
                return reader.readLine();
            } catch (MalformedInputException e) {
                // The reader decodes ahead of the line it returns, so the exact line is unknown.
                throw new StudentExportUnreadableException("file is not valid UTF-8", e);
            } catch (IOException e) {
                throw new StudentExportUnreadableException(
                        "cannot read line " + (lineNumber + 1) + ": " + e.getMessage(), e);
            }
        }
    }
}
