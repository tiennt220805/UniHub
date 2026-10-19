package com.unihub.shared.infrastructure.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;

/**
 * The declared topology against messaging.md §2 (bindings), §4 (retry chains) and §6 (reference
 * table), written out by hand so a typo in {@link Topology} cannot agree with itself.
 */
class TopologyTest {

    private static final String EXCHANGE = "unihub.events";

    private final Declarables declared = Topology.declarables();

    private Map<String, Queue> queues() {
        return Topology.queuesOf(declared).stream()
                .collect(Collectors.toMap(Queue::getName, q -> q));
    }

    private Set<String> bindingsOf(String queue) {
        return Topology.bindingsOf(declared).stream()
                .filter(b -> b.getDestination().equals(queue))
                .map(Binding::getRoutingKey)
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("one durable topic exchange, unihub.events")
    void exchange() {
        var exchanges = Topology.exchangesOf(declared);

        assertThat(exchanges).hasSize(1);
        assertThat(exchanges.getFirst().getName()).isEqualTo(EXCHANGE);
        assertThat(exchanges.getFirst().isDurable()).isTrue();
        assertThat(exchanges.getFirst().isAutoDelete()).isFalse();
    }

    @Test
    @DisplayName("thirty queues: 8 business, 21 retry stages, 1 parking lot — all durable")
    void queueInventory() {
        assertThat(queues()).hasSize(30);
        assertThat(queues().values()).allSatisfy(q -> assertThat(q.isDurable()).isTrue());
        assertThat(queues().values())
                .allSatisfy(q -> assertThat(q.isAutoDelete() || q.isExclusive()).isFalse());
    }

    @Test
    @DisplayName("each business queue is bound to exactly the keys of messaging.md §2")
    void bindings() {
        assertThat(bindingsOf("unihub.notification.queue"))
                .containsExactlyInAnyOrder(
                        "unihub.registration.ticket.confirmed",
                        "unihub.workshop.event.cancelled",
                        "unihub.workshop.event.rescheduled",
                        "unihub.notification.redelivery.requested");
        assertThat(bindingsOf("unihub.payment-confirmation.queue"))
                .containsExactly("unihub.payment.charge.succeeded");
        assertThat(bindingsOf("unihub.payment-refund.queue"))
                .containsExactly("unihub.registration.confirmation.rejected");
        assertThat(bindingsOf("unihub.registration.hold-delay.queue"))
                .containsExactly("unihub.registration.hold.reserved");
        assertThat(bindingsOf("unihub.seat-hold-expiry.queue"))
                .containsExactly("unihub.registration.hold.expired");
        assertThat(bindingsOf("unihub.summarization.queue"))
                .containsExactly("unihub.workshop.summarization.requested");
        assertThat(bindingsOf("unihub.reconciliation.queue"))
                .containsExactly("unihub.payment.reconciliation.requested");
        assertThat(bindingsOf("unihub.audit.queue")).containsExactly("unihub.system.audit.created");
    }

    @Test
    @DisplayName(
            "every binding is from unihub.events; retry stages and the parking lot are unbound")
    void noOtherBindings() {
        var bindings = Topology.bindingsOf(declared);

        assertThat(bindings).hasSize(4 + 1 + 1 + 1 + 1 + 1 + 1 + 1);
        assertThat(bindings).allSatisfy(b -> assertThat(b.getExchange()).isEqualTo(EXCHANGE));
        assertThat(bindingsOf("unihub.parking-lot")).isEmpty();
        assertThat(bindingsOf("unihub.notification.retry.5s")).isEmpty();
    }

    @Test
    @DisplayName("the hold-delay queue expires after exactly 15 minutes into hold.expired")
    void holdDelayQueue() {
        var args = queues().get("unihub.registration.hold-delay.queue").getArguments();

        assertThat(args)
                .containsEntry("x-message-ttl", 900000)
                .containsEntry("x-dead-letter-exchange", EXCHANGE)
                .containsEntry("x-dead-letter-routing-key", "unihub.registration.hold.expired");
    }

    @Test
    @DisplayName(
            "business queues have no dead-letter exchange: retries are an application republish")
    void businessQueuesHaveNoDeadLetterExchange() {
        for (var name :
                List.of(
                        "unihub.notification.queue",
                        "unihub.payment-confirmation.queue",
                        "unihub.payment-refund.queue",
                        "unihub.seat-hold-expiry.queue",
                        "unihub.summarization.queue",
                        "unihub.reconciliation.queue",
                        "unihub.audit.queue")) {
            assertThat(queues().get(name).getArguments()).as(name).isEmpty();
        }
    }

    @Test
    @DisplayName("the parking lot is a plain durable queue: no TTL, no dead-letter, no consumer")
    void parkingLot() {
        assertThat(queues().get("unihub.parking-lot").getArguments()).isEmpty();
    }

    @Test
    @DisplayName("each retry chain is 5s -> 30s -> 5m and dead-letters to its own fixed key")
    void retryChains() {
        var expected =
                Map.of(
                        "unihub.notification", "unihub.notification.redelivery.requested",
                        "unihub.seat-hold-expiry", "unihub.registration.hold.expired",
                        "unihub.summarization", "unihub.workshop.summarization.requested",
                        "unihub.reconciliation", "unihub.payment.reconciliation.requested",
                        "unihub.audit", "unihub.system.audit.created",
                        "unihub.payment-confirmation", "unihub.payment.charge.succeeded",
                        "unihub.payment-refund", "unihub.registration.confirmation.rejected");
        var ttl = Map.of("5s", 5_000, "30s", 30_000, "5m", 300_000);

        expected.forEach(
                (base, deadLetterKey) ->
                        ttl.forEach(
                                (stage, millis) ->
                                        assertThat(queues().get(base + ".retry." + stage))
                                                .as(base + " " + stage)
                                                .isNotNull()
                                                .satisfies(
                                                        q ->
                                                                assertThat(q.getArguments())
                                                                        .containsEntry(
                                                                                "x-message-ttl",
                                                                                millis)
                                                                        .containsEntry(
                                                                                "x-dead-letter-exchange",
                                                                                EXCHANGE)
                                                                        .containsEntry(
                                                                                "x-dead-letter-routing-key",
                                                                                deadLetterKey))));
    }

    @Test
    @DisplayName("every dead-letter key of a retry chain is bound back to its own business queue")
    void deadLetterKeysReachTheBusinessQueue() {
        var chainOwner =
                Map.of(
                        "unihub.notification.redelivery.requested", "unihub.notification.queue",
                        "unihub.registration.hold.expired", "unihub.seat-hold-expiry.queue",
                        "unihub.workshop.summarization.requested", "unihub.summarization.queue",
                        "unihub.payment.reconciliation.requested", "unihub.reconciliation.queue",
                        "unihub.system.audit.created", "unihub.audit.queue",
                        "unihub.payment.charge.succeeded", "unihub.payment-confirmation.queue",
                        "unihub.registration.confirmation.rejected", "unihub.payment-refund.queue");

        chainOwner.forEach((key, owner) -> assertThat(bindingsOf(owner)).contains(key));
    }

    @Test
    @DisplayName("stage queue names are derived from the business queue name")
    void retryStageNames() {
        assertThat(Topology.retryStageQueues("unihub.payment-confirmation.queue"))
                .containsExactly(
                        "unihub.payment-confirmation.retry.5s",
                        "unihub.payment-confirmation.retry.30s",
                        "unihub.payment-confirmation.retry.5m");
    }
}
