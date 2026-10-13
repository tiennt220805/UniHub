package com.unihub.user.infrastructure.out.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import com.unihub.user.application.port.out.ExportLine;
import com.unihub.user.application.port.out.StudentExportUnreadableException;
import com.unihub.user.infrastructure.config.NightlySyncProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSystemStudentExportSourceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 13);

    @TempDir Path inbound;

    private FileSystemStudentExportSource source() {
        return new FileSystemStudentExportSource(
                new NightlySyncProperties(
                        true, "0 0 2 * * *", ZoneId.of("Asia/Ho_Chi_Minh"), inbound, 500));
    }

    @Test
    @DisplayName("the file is named after the export date")
    void fileName() {
        assertThat(FileSystemStudentExportSource.fileName(DATE))
                .isEqualTo("students_export_20261013.csv");
    }

    @Test
    @DisplayName("no file for the date: empty")
    void missingFile_empty() throws IOException {
        Files.writeString(inbound.resolve("students_export_20261012.csv"), "x");

        assertThat(source().find(DATE)).isEmpty();
    }

    @Test
    @DisplayName("reads every line in order, numbered from the header, CRLF or LF alike")
    void readsLines() throws IOException {
        Files.writeString(
                inbound.resolve("students_export_20261013.csv"),
                "legacy_student_id,email,full_name\r\nSV001,a@uni.edu,Trần Thị B\nSV002,\"b\n",
                StandardCharsets.UTF_8);

        var export = source().find(DATE).orElseThrow();

        try (var lines = export.lines()) {
            assertThat(lines.toList())
                    .extracting(ExportLine::lineNumber, ExportLine::raw)
                    .containsExactly(
                            tuple(1, "legacy_student_id,email,full_name"),
                            tuple(2, "SV001,a@uni.edu,Trần Thị B"),
                            tuple(3, "SV002,\"b"));
        }
    }

    @Test
    @DisplayName("can be read more than once (two passes)")
    void readsTwice() throws IOException {
        Files.writeString(inbound.resolve("students_export_20261013.csv"), "a\nb\n");
        var export = source().find(DATE).orElseThrow();

        try (var first = export.lines();
                var second = export.lines()) {
            assertThat(first.count()).isEqualTo(2);
            assertThat(second.count()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("bytes that are not UTF-8 make the file unreadable")
    void invalidUtf8_unreadable() throws IOException {
        Files.write(
                inbound.resolve("students_export_20261013.csv"),
                new byte[] {'a', '\n', (byte) 0xC3, (byte) 0x28, '\n'});
        var export = source().find(DATE).orElseThrow();

        try (var lines = export.lines()) {
            assertThatThrownBy(lines::toList)
                    .isInstanceOf(StudentExportUnreadableException.class)
                    .hasMessageContaining("not valid UTF-8");
        }
    }
}
