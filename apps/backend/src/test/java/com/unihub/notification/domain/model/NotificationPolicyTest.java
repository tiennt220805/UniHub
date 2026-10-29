package com.unihub.notification.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NotificationPolicyTest {

    private static final Map<NotificationType, List<String>> ALL =
            Map.of(
                    NotificationType.REGISTRATION_CONFIRMED, List.of("EMAIL", "IN_APP"),
                    NotificationType.WORKSHOP_CANCELLED, List.of("EMAIL"),
                    NotificationType.WORKSHOP_RESCHEDULED, List.of("IN_APP"));

    @Test
    @DisplayName("each type has its own channels")
    void channelsPerType() {
        var policy = new NotificationPolicy(Duration.ofSeconds(120), ALL);

        assertThat(policy.channelsOf(NotificationType.WORKSHOP_CANCELLED)).containsExactly("EMAIL");
        assertThat(policy.channelsOf(NotificationType.REGISTRATION_CONFIRMED))
                .containsExactly("EMAIL", "IN_APP");
    }

    @Test
    @DisplayName("a type with no channel, or a non-positive lease, is refused")
    void invalid_isRefused() {
        assertThatThrownBy(
                        () ->
                                new NotificationPolicy(
                                        Duration.ofSeconds(1),
                                        Map.of(
                                                NotificationType.WORKSHOP_CANCELLED,
                                                List.of("EMAIL"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NotificationPolicy(Duration.ZERO, ALL))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
