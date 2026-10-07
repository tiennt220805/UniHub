package com.unihub.shared.domain.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UserIdTest {

    @Test
    @DisplayName("rejects a null value")
    void constructor_rejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> new UserId(null));
    }

    @Test
    @DisplayName("of() wraps the given UUID and compares by value")
    void of_wrapsUuid() {
        var uuid = UUID.randomUUID();

        assertThat(UserId.of(uuid).value()).isEqualTo(uuid);
        assertThat(UserId.of(uuid)).isEqualTo(UserId.of(uuid));
    }

    @Test
    @DisplayName("newId() generates a distinct id each call")
    void newId_isUnique() {
        assertThat(UserId.newId()).isNotEqualTo(UserId.newId());
    }
}
