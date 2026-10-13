package com.unihub.user.infrastructure.out.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CsvLineTokenizerTest {

    @Test
    @DisplayName("splits plain fields and keeps the raw line and its number")
    void plainFields() {
        var line = CsvLineTokenizer.tokenize(7, "SV001,a@uni.edu,Nguyen Van A");

        assertThat(line.lineNumber()).isEqualTo(7);
        assertThat(line.raw()).isEqualTo("SV001,a@uni.edu,Nguyen Van A");
        assertThat(line.fields()).containsExactly("SV001", "a@uni.edu", "Nguyen Van A");
        assertThat(line.malformation()).isEmpty();
    }

    @Test
    @DisplayName("a quoted field may hold commas and escaped quotes")
    void quotedFields() {
        var line = CsvLineTokenizer.tokenize(2, "SV001,\"a@uni.edu\",\"Nguyen, \"\"An\"\"\"");

        assertThat(line.fields()).containsExactly("SV001", "a@uni.edu", "Nguyen, \"An\"");
    }

    @Test
    @DisplayName("empty fields, including a trailing one, are kept")
    void emptyFields() {
        assertThat(CsvLineTokenizer.tokenize(2, "a,,b,").fields())
                .containsExactly("a", "", "b", "");
        assertThat(CsvLineTokenizer.tokenize(2, "").fields()).containsExactly("");
    }

    @Test
    @DisplayName("an unterminated quote is malformed")
    void unterminatedQuote() {
        var line = CsvLineTokenizer.tokenize(3, "SV001,\"a@uni.edu,Name");

        assertThat(line.malformation()).contains("unterminated quoted field");
        assertThat(line.fields()).isEmpty();
        assertThat(line.raw()).isEqualTo("SV001,\"a@uni.edu,Name");
    }

    @Test
    @DisplayName("text after a closing quote is malformed")
    void textAfterClosingQuote() {
        assertThat(CsvLineTokenizer.tokenize(3, "\"SV001\"x,a,b").malformation())
                .contains("text after a closing quote at position 8");
    }

    @Test
    @DisplayName("a NUL character is malformed (Postgres text cannot store it)")
    void nulCharacter() {
        assertThat(CsvLineTokenizer.tokenize(3, "SV\u0000001,a,b").malformation())
                .contains("contains a NUL character");
    }
}
