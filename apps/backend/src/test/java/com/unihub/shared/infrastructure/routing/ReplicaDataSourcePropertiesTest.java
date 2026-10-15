package com.unihub.shared.infrastructure.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** M-13 (INV-18): the Replica credentials are injected at runtime and never printed. */
class ReplicaDataSourcePropertiesTest {

    @Test
    @DisplayName("an unset url is allowed: read-only transactions then use the Master pool")
    void unsetUrl_accepted() {
        assertThat(new ReplicaDataSourceProperties(null, null, null).url()).isNull();
    }

    @Test
    @DisplayName("a blank url is refused instead of silently meaning 'no Replica'")
    void blankUrl_refused() {
        assertThatThrownBy(() -> new ReplicaDataSourceProperties(" ", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unihub.datasource.replica.url");
    }

    @Test
    @DisplayName("toString never shows the password")
    void toString_redactsPassword() {
        var properties =
                new ReplicaDataSourceProperties(
                        "jdbc:postgresql://postgres-replica:5432/unihub", "unihub", "s3cr3t-value");

        assertThat(properties.toString()).doesNotContain("s3cr3t-value").contains("password=****");
    }
}
