package com.unihub.shared.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VersionTest {

    @Test
    @DisplayName("initial version is 0, matching the schema's version column DEFAULT 0")
    void initial_isZero() {
        assertThat(Version.initial().value()).isZero();
    }

    @Test
    @DisplayName("two versions with the same value are equal")
    void equals_comparesByValue() {
        assertThat(new Version(3)).isEqualTo(new Version(3)).isNotEqualTo(new Version(4));
    }
}
