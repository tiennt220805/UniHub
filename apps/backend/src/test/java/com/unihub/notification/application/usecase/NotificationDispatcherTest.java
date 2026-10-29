package com.unihub.notification.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unihub.notification.application.port.out.NotificationChannel;
import com.unihub.notification.application.port.out.NotificationDeliveryResult;
import com.unihub.notification.application.port.out.NotificationDispatch;
import com.unihub.notification.domain.model.NotificationType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NotificationDispatcherTest {

    private static NotificationChannel channel(String code, NotificationDeliveryResult result) {
        return new NotificationChannel() {
            @Override
            public String channelCode() {
                return code;
            }

            @Override
            public NotificationDeliveryResult send(NotificationDispatch dispatch) {
                return result;
            }
        };
    }

    private static NotificationDispatch dispatch() {
        return new NotificationDispatch(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "a@example.test",
                NotificationType.WORKSHOP_CANCELLED,
                Map.of());
    }

    @Test
    @DisplayName("a dispatch goes to the channel with that code and returns its result")
    void dispatch_resolvesByCode() {
        var dispatcher =
                new NotificationDispatcher(
                        List.of(
                                channel("EMAIL", NotificationDeliveryResult.DEFERRED),
                                channel("IN_APP", NotificationDeliveryResult.SENT)));

        assertThat(dispatcher.dispatch("EMAIL", dispatch()))
                .isEqualTo(NotificationDeliveryResult.DEFERRED);
        assertThat(dispatcher.dispatch("IN_APP", dispatch()))
                .isEqualTo(NotificationDeliveryResult.SENT);
        assertThat(dispatcher.channelCodes()).containsExactly("EMAIL", "IN_APP");
    }

    @Test
    @DisplayName("a code with no bean is a schema/code drift, not a delivery result")
    void unknownCode_throws() {
        var dispatcher =
                new NotificationDispatcher(
                        List.of(channel("EMAIL", NotificationDeliveryResult.SENT)));

        assertThatThrownBy(() -> dispatcher.dispatch("SMS", dispatch()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SMS");
    }

    @Test
    @DisplayName("two beans claiming one code stop the start")
    void duplicateCode_throws() {
        assertThatThrownBy(
                        () ->
                                new NotificationDispatcher(
                                        List.of(
                                                channel("EMAIL", NotificationDeliveryResult.SENT),
                                                channel("EMAIL", NotificationDeliveryResult.SENT))))
                .isInstanceOf(IllegalStateException.class);
    }
}
