package com.unihub.user.application.usecase;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NightlySyncPolicyTest {

    @Test
    @DisplayName("a batch size below 1 is refused at startup")
    void batchSizeBelowOne_refused() {
        assertThatThrownBy(() -> new NightlySyncPolicy(ZoneId.of("Asia/Ho_Chi_Minh"), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
