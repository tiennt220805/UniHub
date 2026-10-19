package com.unihub.shared.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unihub.shared.application.port.out.BrokerPublishException;
import com.unihub.shared.application.port.out.BrokerUnavailableException;
import com.unihub.shared.application.port.out.MessageBroker;
import com.unihub.shared.application.port.out.PendingEvent;
import com.unihub.shared.application.port.out.PendingEventStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RelayPendingEventsTest {

    private final List<PendingEvent> pending = new ArrayList<>();
    private final List<UUID> published = new ArrayList<>();
    private final List<UUID> marked = new ArrayList<>();
    private final Set<UUID> alreadyPublishedElsewhere = new java.util.HashSet<>();
    private int requestedLimit;

    private final PendingEventStore store =
            new PendingEventStore() {
                @Override
                public List<PendingEvent> findPending(int limit) {
                    requestedLimit = limit;
                    return List.copyOf(pending);
                }

                @Override
                public boolean markPublished(UUID id) {
                    marked.add(id);
                    return !alreadyPublishedElsewhere.contains(id);
                }
            };

    private MessageBroker broker = event -> published.add(event.id());

    private PendingEvent event(String routingKey) {
        var event = new PendingEvent(UUID.randomUUID(), routingKey, "{}", null);
        pending.add(event);
        return event;
    }

    private RelayPendingEvents relay() {
        return new RelayPendingEvents(store, broker);
    }

    @Test
    @DisplayName("publishes each pending row in order, then marks it PUBLISHED")
    void publishesThenMarks() {
        var first = event("a");
        var second = event("b");

        int count = relay().relay(200);

        assertThat(count).isEqualTo(2);
        assertThat(published).containsExactly(first.id(), second.id());
        assertThat(marked).containsExactly(first.id(), second.id());
        assertThat(requestedLimit).isEqualTo(200);
    }

    @Test
    @DisplayName("a row the broker refuses stays PENDING and the rows behind it still go")
    void failedRow_doesNotBlockTheRest() {
        var bad = event("unbound");
        var good = event("ok");
        broker =
                e -> {
                    if (e.id().equals(bad.id())) {
                        throw new BrokerPublishException("unroutable");
                    }
                    published.add(e.id());
                };

        int count = relay().relay(200);

        assertThat(count).isEqualTo(1);
        assertThat(published).containsExactly(good.id());
        assertThat(marked).containsExactly(good.id()).doesNotContain(bad.id());
    }

    @Test
    @DisplayName("a row is never marked PUBLISHED before the broker has confirmed it")
    void marksOnlyAfterTheBrokerReturns() {
        var only = event("a");
        broker =
                e -> {
                    assertThat(marked).as("marked before publish returned").isEmpty();
                    published.add(e.id());
                };

        relay().relay(200);

        assertThat(marked).containsExactly(only.id());
    }

    @Test
    @DisplayName("an unreachable broker ends the cycle instead of waiting once per row")
    void brokerUnavailable_endsTheCycle() {
        event("a");
        event("b");
        event("c");
        int[] attempts = {0};
        broker =
                e -> {
                    attempts[0]++;
                    throw new BrokerUnavailableException("connection refused", null);
                };

        int count = relay().relay(200);

        assertThat(count).isZero();
        assertThat(attempts[0]).isEqualTo(1);
        assertThat(marked).isEmpty();
    }

    @Test
    @DisplayName("a row another run already published is not counted twice")
    void alreadyPublished_isNotCounted() {
        var raced = event("a");
        alreadyPublishedElsewhere.add(raced.id());

        assertThat(relay().relay(200)).isZero();
    }

    @Test
    @DisplayName("a database failure while marking aborts the cycle and surfaces")
    void markFailure_propagates() {
        event("a");
        event("b");
        var failing =
                new RelayPendingEvents(
                        new PendingEventStore() {
                            @Override
                            public List<PendingEvent> findPending(int limit) {
                                return List.copyOf(pending);
                            }

                            @Override
                            public boolean markPublished(UUID id) {
                                throw new IllegalStateException("db down");
                            }
                        },
                        broker);

        assertThatThrownBy(() -> failing.relay(200)).isInstanceOf(IllegalStateException.class);
        assertThat(published).as("rows after the failure are not published").hasSize(1);
    }

    @Test
    @DisplayName("nothing pending is a quiet no-op")
    void nothingPending() {
        assertThat(relay().relay(200)).isZero();
        assertThat(published).isEmpty();
    }

    @Test
    @DisplayName("a non-positive batch size is refused")
    void nonPositiveBatchSize() {
        assertThatThrownBy(() -> relay().relay(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
