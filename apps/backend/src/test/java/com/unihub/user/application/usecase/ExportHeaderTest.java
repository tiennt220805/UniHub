package com.unihub.user.application.usecase;

import static com.unihub.user.application.usecase.ExportLines.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unihub.user.application.port.out.ExportLine;
import com.unihub.user.application.port.out.StudentExportUnreadableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExportHeaderTest {

    @Test
    @DisplayName("finds the required columns in any order, trimmed and case-insensitive")
    void anyOrderAndCase() {
        var header = ExportHeader.parse(line(1, " Full_Name ,EMAIL,legacy_student_id,faculty"));
        var row = line(2, "Nguyen Van A, a@uni.edu , SV001 ,IT");

        assertThat(header.misfit(row)).isEmpty();
        assertThat(header.legacyStudentId(row)).isEqualTo("SV001");
        assertThat(header.email(row)).isEqualTo("a@uni.edu");
        assertThat(header.fullName(row)).isEqualTo("Nguyen Van A");
    }

    @Test
    @DisplayName("ignores a UTF-8 byte order mark before the first column name")
    void byteOrderMark() {
        var header = ExportHeader.parse(line(1, "﻿legacy_student_id,email,full_name"));

        assertThat(header.legacyStudentId(line(2, "SV001,a@uni.edu,A"))).isEqualTo("SV001");
    }

    @Test
    @DisplayName("a row with a different column count does not fit")
    void columnCountMismatch() {
        var header = ExportHeader.parse(line(1, ExportLines.HEADER));

        assertThat(header.misfit(line(2, "SV001,a@uni.edu")))
                .contains("expected 3 columns, found 2");
        assertThat(header.misfit(ExportLine.malformed(3, "x", "unterminated quoted field")))
                .contains("unterminated quoted field");
    }

    @Test
    @DisplayName("a missing required column makes the file unreadable")
    void missingColumn() {
        assertThatThrownBy(() -> ExportHeader.parse(line(1, "legacy_student_id,email")))
                .isInstanceOf(StudentExportUnreadableException.class)
                .hasMessage("header lacks required column 'full_name'");
    }

    @Test
    @DisplayName("a repeated column makes the file unreadable")
    void repeatedColumn() {
        assertThatThrownBy(
                        () ->
                                ExportHeader.parse(
                                        line(1, "legacy_student_id,email,full_name,Email")))
                .isInstanceOf(StudentExportUnreadableException.class)
                .hasMessage("header repeats column 'email'");
    }

    @Test
    @DisplayName("a malformed header row makes the file unreadable")
    void malformedHeader() {
        assertThatThrownBy(
                        () ->
                                ExportHeader.parse(
                                        ExportLine.malformed(
                                                1, "\"x", "unterminated quoted field")))
                .isInstanceOf(StudentExportUnreadableException.class)
                .hasMessage("header row is malformed: unterminated quoted field");
    }
}
