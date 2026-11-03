package com.unihub;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.unihub.shared.archfixture.UnboundOutboxMessage;
import com.unihub.shared.event.OutboxMessage;
import com.unihub.shared.infrastructure.out.messaging.Topology;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;

/**
 * INV-10, the half that is easy to get wrong silently (Phase 4 checklist 7.1, D42, AC42.1, M-41): a
 * message the outbox relays to a routing key that no consumed queue is bound to is acknowledged by
 * RabbitMQ and dropped, and its row says {@code PUBLISHED}. Nothing fails; nobody receives it.
 *
 * <p>Every implementation of {@link OutboxMessage} among the production classes is found, not just
 * those under {@code api.event} ({@code SeatHoldExpiryRequested} lives in {@code
 * registration.application.event}); its {@code ROUTING_KEY} is followed through the declared
 * bindings to a queue that has a {@code @RabbitListener}. The count is pinned, so a tenth message
 * type cannot arrive without being added here, with the end-to-end test that shows its effect.
 *
 * <p>One route is longer than a binding: {@code hold.reserved} is bound to the delay queue, which
 * has no consumer by design and is dead-lettered back to the exchange as {@code hold.expired} when
 * its TTL runs out. The test follows that hop and pins it as the only one.
 */
class OutboxRoutingCoverageTest {

    /** The outbox message → the test that shows its effect after a real relay (M-41, AC42.2). */
    static final Map<String, String> END_TO_END = new LinkedHashMap<>();

    private static final String NOTIFICATION = "com.unihub.notification.";
    private static final String PAYMENT = "com.unihub.payment.";
    private static final String WORKSHOP = "com.unihub.workshop.brochure.";

    static {
        END_TO_END.put(
                "RegistrationConfirmedIntegrationEvent",
                NOTIFICATION
                        + "RegistrationConfirmationNotificationIT"
                        + "#freeRegistration_isConfirmedByEmailAndInApp");
        END_TO_END.put(
                "WorkshopCancelledIntegrationEvent",
                NOTIFICATION + "WorkshopChangeNotificationIT#cancel_notifiesEveryoneHoldingASeat");
        END_TO_END.put(
                "WorkshopRescheduledIntegrationEvent",
                NOTIFICATION + "WorkshopChangeNotificationIT#reschedule_notifiesOncePerChange");
        END_TO_END.put(
                "PaymentSucceededIntegrationEvent",
                NOTIFICATION
                        + "PaidRegistrationNotificationIT"
                        + "#paidRegistration_isConfirmedByEmailAndInApp");
        END_TO_END.put(
                "SeatHoldExpiryRequested",
                NOTIFICATION
                        + "PaidRegistrationNotificationIT"
                        + "#lateSuccess_isRefundedAndNothingIsSent");
        END_TO_END.put(
                "RegistrationConfirmationRejectedIntegrationEvent",
                NOTIFICATION
                        + "PaidRegistrationNotificationIT"
                        + "#lateSuccess_isRefundedAndNothingIsSent");
        END_TO_END.put(
                "PaymentRefundRequested",
                NOTIFICATION
                        + "PaidRegistrationNotificationIT"
                        + "#lateSuccess_isRefundedAndNothingIsSent");
        END_TO_END.put(
                "BrochureSummarizationRequested",
                WORKSHOP
                        + "BrochureRelayEndToEndIT"
                        + "#summarizationRequest_reachesTheConsumerThroughTheRealRelay");
        END_TO_END.put(
                "PaymentReconciliationRequested",
                PAYMENT
                        + "infrastructure.in.messaging.OutboxRelayEndToEndIT"
                        + "#staleSweep_reachesTheReconciliationConsumer");
    }

    /** The one route that reaches its consumer through a dead-letter hop. */
    private static final String VIA_DELAY_QUEUE = "SeatHoldExpiryRequested";

    private static final Path INTEGRATION_TEST_SOURCES = Path.of("src/integrationTest/java");
    private static final Path MATRIX = Path.of("../../docs/testing/reports/test-matrix.md");

    private static JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.unihub");
    }

    /** Every concrete implementation of {@link OutboxMessage} among these classes. */
    static List<JavaClass> outboxMessages(JavaClasses classes) {
        return classes.stream()
                .filter(c -> !c.isInterface() && c.isAssignableTo(OutboxMessage.class))
                .toList();
    }

    static String routingKeyOf(JavaClass type) {
        try {
            return (String) type.reflect().getField("ROUTING_KEY").get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(
                    type.getName()
                            + " must declare a public static String ROUTING_KEY (the exact key of"
                            + " messaging.md §2)",
                    e);
        }
    }

    /** Whether the key ends at a queue with a listener, directly or through a delay queue. */
    static Route routeOf(String routingKey, TreeSet<String> listened) {
        var declarables = Topology.declarables();
        var queues = new TreeMap<String, Queue>();
        declarables.getDeclarablesByType(Queue.class).forEach(q -> queues.put(q.getName(), q));
        var bindings = declarables.getDeclarablesByType(Binding.class);

        var key = routingKey;
        boolean throughDelay = false;
        for (int hop = 0; hop < 3; hop++) {
            var current = key;
            var destinations =
                    bindings.stream()
                            .filter(b -> b.getRoutingKey().equals(current))
                            .map(Binding::getDestination)
                            .toList();
            if (destinations.isEmpty()) {
                return new Route(false, throughDelay, "no queue is bound to " + current);
            }
            if (destinations.stream().anyMatch(listened::contains)) {
                return new Route(true, throughDelay, "");
            }
            var deadLetterKeys =
                    destinations.stream()
                            .map(queues::get)
                            .map(
                                    q ->
                                            q == null
                                                            || !Topology.EXCHANGE.equals(
                                                                    q.getArguments()
                                                                            .get(
                                                                                    "x-dead-letter-exchange"))
                                                    ? null
                                                    : q.getArguments()
                                                            .get("x-dead-letter-routing-key"))
                            .filter(String.class::isInstance)
                            .map(String.class::cast)
                            .toList();
            if (deadLetterKeys.isEmpty()) {
                return new Route(
                        false,
                        throughDelay,
                        current
                                + " is bound to "
                                + destinations
                                + ", none of which has a listener");
            }
            key = deadLetterKeys.getFirst();
            throughDelay = true;
        }
        return new Route(false, throughDelay, "no consumer within three hops of " + routingKey);
    }

    record Route(boolean consumed, boolean throughDelayQueue, String problem) {}

    /** Simple class name → what is wrong with its route; empty when all is well. */
    static Map<String, String> unrouted(List<JavaClass> types, TreeSet<String> listened) {
        var problems = new TreeMap<String, String>();
        for (var type : types) {
            var route = routeOf(routingKeyOf(type), listened);
            if (!route.consumed()) {
                problems.put(type.getSimpleName(), route.problem());
            }
        }
        return problems;
    }

    private static TreeSet<String> listened() {
        return ConsumerRedeliveryCoverageTest.listenedQueues(productionClasses());
    }

    @Test
    @DisplayName("AC42.1: there are exactly nine outbox message types, all known to this test")
    void theNineMessageTypes() {
        var found =
                outboxMessages(productionClasses()).stream()
                        .map(JavaClass::getSimpleName)
                        .collect(java.util.stream.Collectors.toCollection(TreeSet::new));

        assertThat(found)
                .as(
                        "a new OutboxMessage needs a binding to a consumed queue, a line in"
                                + " END_TO_END with the test that shows its effect, and the M-41 row")
                .hasSize(9)
                .containsExactlyInAnyOrderElementsOf(END_TO_END.keySet());
    }

    @Test
    @DisplayName("AC42.1: every outbox message's routing key ends at a queue that has a listener")
    void everyRoutingKey_endsAtAConsumer() {
        assertThat(unrouted(outboxMessages(productionClasses()), listened()))
                .as("outbox messages that RabbitMQ would acknowledge and drop")
                .isEmpty();
    }

    @Test
    @DisplayName("hold.reserved is the only route that goes through the delay queue")
    void onlyTheHoldJobUsesTheDelayQueue() {
        var listened = listened();
        var delayed =
                outboxMessages(productionClasses()).stream()
                        .filter(t -> routeOf(routingKeyOf(t), listened).throughDelayQueue())
                        .map(JavaClass::getSimpleName)
                        .toList();

        assertThat(delayed)
                .as("a second delay-queue route has to be added here on purpose")
                .containsExactly(VIA_DELAY_QUEUE);
        assertThat(
                        routeOf(
                                Topology.HOLD_RESERVED,
                                new TreeSet<>(List.of("unihub.seat-hold-expiry.queue"))))
                .as("the delay queue dead-letters to the key the expiry consumer is bound to")
                .extracting(Route::consumed)
                .isEqualTo(true);
    }

    @Test
    @DisplayName("AC42.1: a message type whose key no queue is bound to is reported")
    void anUnboundMessage_isReported() {
        var classes = new ClassFileImporter().importClasses(UnboundOutboxMessage.class);

        assertThat(unrouted(outboxMessages(classes), listened()))
                .containsOnlyKeys("UnboundOutboxMessage")
                .extractingByKey("UnboundOutboxMessage")
                .asString()
                .contains("no queue is bound");
    }

    @Test
    @DisplayName("a key bound to a queue nobody listens on is reported (a consumer not built yet)")
    void aBoundQueueWithoutAListener_isReported() {
        // The audit consumer exists since Phase 6 (M-54); a listener set without it stands for the
        // days before, so the check on a bound-but-unlistened queue still has an example.
        var audit = routeOf(Topology.AUDIT_CREATED, new TreeSet<>());

        assertThat(audit.consumed())
                .as("unihub.audit.queue with no listener is not consumed")
                .isFalse();
        assertThat(audit.problem()).contains("none of which has a listener");
    }

    @Test
    @DisplayName("every end-to-end test the table names exists in the integration-test sources")
    void everyNamedTest_exists() throws IOException {
        for (var entry : END_TO_END.entrySet()) {
            var reference = entry.getValue();
            var className = reference.substring(0, reference.indexOf('#'));
            var method = reference.substring(reference.indexOf('#') + 1);
            var source = INTEGRATION_TEST_SOURCES.resolve(className.replace('.', '/') + ".java");
            assertThat(source).as(entry.getKey() + " -> " + reference).exists();
            assertThat(Files.readString(source))
                    .as(entry.getKey() + " -> " + reference)
                    .contains("void " + method + "(");
        }
    }

    @Test
    @DisplayName("the M-41 row of the test matrix names every routing key and every test")
    void matrixRow_matchesTheTable() throws IOException {
        assertThat(MATRIX).as("run from apps/backend, as Gradle does").exists();
        var row =
                Files.readAllLines(MATRIX).stream()
                        .filter(line -> line.startsWith("| M-41 |"))
                        .findFirst()
                        .orElseThrow();

        for (var type : outboxMessages(productionClasses())) {
            assertThat(row).as("routing key").contains(routingKeyOf(type));
            var reference = END_TO_END.get(type.getSimpleName());
            var simpleClass =
                    reference.substring(reference.lastIndexOf('.') + 1, reference.indexOf('#'));
            var method = reference.substring(reference.indexOf('#') + 1);
            assertThat(row).as(reference).contains(simpleClass).contains(method);
        }
    }
}
