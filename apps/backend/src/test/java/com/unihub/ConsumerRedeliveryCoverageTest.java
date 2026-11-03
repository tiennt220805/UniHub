package com.unihub;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.unihub.checkin.archfixture.UncoveredConsumer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

/**
 * INV-11 cannot be a promise about the consumers that exist today (Phase 4 checklist D41, AC41.2,
 * M-40): this test fails for a queue that has a {@code @RabbitListener} and no entry in the
 * inventory below, so a consumer added later (the audit consumer of Phase 6, the summarization
 * consumer of Phase 5) cannot arrive without a sequential and a concurrent redelivery test.
 *
 * <p>The inventory is checked three ways, so that it cannot rot: every test it names exists as a
 * method in the integration-test sources; the M-40 row of the test matrix names every queue and
 * every test; and a listener on a queue with no entry (a fixture class here) is reported.
 */
class ConsumerRedeliveryCoverageTest {

    /** The sequential (one message, three deliveries) and concurrent (two at once) evidence. */
    record Coverage(String sequential, String concurrent) {}

    private static final String REGISTRATION =
            "com.unihub.registration.infrastructure.in.messaging.";
    private static final String PAYMENT = "com.unihub.payment.infrastructure.in.messaging.";
    private static final String NOTIFICATION = "com.unihub.notification.";
    private static final String WORKSHOP = "com.unihub.workshop.brochure.";
    private static final String SHARED = "com.unihub.shared.infrastructure.in.messaging.";

    /** Queue → the tests that show its consumer idempotent under redelivery. */
    static final Map<String, Coverage> INVENTORY = new LinkedHashMap<>();

    static {
        INVENTORY.put(
                "unihub.payment-confirmation.queue",
                new Coverage(
                        REGISTRATION + "PaymentConfirmationIT#redelivery_isNoOp",
                        REGISTRATION
                                + "RegistrationConsumersConcurrentRedeliveryIT"
                                + "#sameSuccess_fromTwoThreads_confirmsOnce"));
        INVENTORY.put(
                "unihub.seat-hold-expiry.queue",
                new Coverage(
                        REGISTRATION + "SeatHoldExpiryIT#redelivery_returnsTheSeatOnce",
                        REGISTRATION
                                + "RegistrationConsumersConcurrentRedeliveryIT"
                                + "#sameHoldExpired_fromTwoThreads_returnsTheSeatOnce"));
        INVENTORY.put(
                "unihub.payment-refund.queue",
                new Coverage(
                        PAYMENT
                                + "PaymentCompensationIT"
                                + "#redeliveredSuccessAndRejection_refundRequiredExactlyOnce",
                        PAYMENT
                                + "PaymentConsumersConcurrentRedeliveryIT"
                                + "#sameRejection_fromTwoThreads_requiresTheRefundOnce"));
        INVENTORY.put(
                "unihub.reconciliation.queue",
                new Coverage(
                        PAYMENT
                                + "ReconciliationRequestedListenerIT"
                                + "#request_resolvesTheStuckPayment_withoutAWebhook",
                        PAYMENT
                                + "PaymentConsumersConcurrentRedeliveryIT"
                                + "#sameReconciliation_fromTwoThreads_resolvesOnce"));
        INVENTORY.put(
                "unihub.payment-refund-execution.queue",
                new Coverage(
                        PAYMENT + "RefundIT#refunded_isNoOp",
                        PAYMENT
                                + "RefundConcurrentRedeliveryIT"
                                + "#sameRefundJob_fromTwoThreads_usesOneIdempotencyKey"));
        INVENTORY.put(
                "unihub.summarization.queue",
                new Coverage(
                        WORKSHOP
                                + "BrochureRedeliveryIT"
                                + "#sequentialRedelivery_callsTheAiOncePerDelivery",
                        WORKSHOP
                                + "BrochureRedeliveryIT"
                                + "#sameRequest_fromTwoThreads_endsWithOneSummary"));
        INVENTORY.put(
                "unihub.notification.queue",
                new Coverage(
                        NOTIFICATION + "NotificationRedeliveryIT#sequentialRedelivery_sendsOnce",
                        NOTIFICATION + "NotificationRedeliveryIT#concurrentRedelivery_sendsOnce"));
        INVENTORY.put(
                "unihub.audit.queue",
                new Coverage(
                        SHARED + "AuditRedeliveryIT#sequentialRedelivery_writesOneRow",
                        SHARED + "AuditRedeliveryIT#concurrentRedelivery_writesOneRow"));
    }

    private static final Path INTEGRATION_TEST_SOURCES = Path.of("src/integrationTest/java");
    private static final Path MATRIX = Path.of("../../docs/testing/reports/test-matrix.md");

    /** Every queue a {@code @RabbitListener} method of these classes listens on. */
    static TreeSet<String> listenedQueues(JavaClasses classes) {
        var queues = new TreeSet<String>();
        classes.stream()
                .flatMap(c -> c.getMethods().stream())
                .filter(m -> m.isAnnotatedWith(RabbitListener.class))
                .forEach(
                        m ->
                                queues.addAll(
                                        List.of(
                                                m.getAnnotationOfType(RabbitListener.class)
                                                        .queues())));
        return queues;
    }

    /** The queues in {@code listened} that the inventory has no entry for. */
    static TreeSet<String> uncovered(TreeSet<String> listened) {
        var missing = new TreeSet<>(listened);
        missing.removeAll(INVENTORY.keySet());
        return missing;
    }

    private static JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.unihub");
    }

    @Test
    @DisplayName("every queue with a @RabbitListener has an entry in the redelivery inventory")
    void everyListenedQueue_hasAnEntry() {
        var listened = listenedQueues(productionClasses());

        assertThat(listened).as("the listeners found in the production classes").isNotEmpty();
        assertThat(uncovered(listened))
                .as(
                        "a listener without a sequential and a concurrent redelivery test: add the"
                                + " tests and an entry to INVENTORY (and to the M-40 row of the"
                                + " test matrix)")
                .isEmpty();
    }

    @Test
    @DisplayName("the inventory lists no queue that nothing listens on any more")
    void everyEntry_isStillListenedTo() {
        assertThat(INVENTORY.keySet())
                .as("an entry for a removed consumer is a test nobody should keep running")
                .isSubsetOf(listenedQueues(productionClasses()));
    }

    @Test
    @DisplayName("AC41.2: a listener on a queue with no entry is reported")
    void aListenerWithoutAnEntry_isReported() {
        var classes = new ClassFileImporter().importClasses(UncoveredConsumer.class);

        assertThat(uncovered(listenedQueues(classes)))
                .containsExactly("unihub.fixture.uncovered.queue");
    }

    @Test
    @DisplayName("every test the inventory names exists in the integration-test sources")
    void everyNamedTest_exists() throws IOException {
        for (var entry : INVENTORY.entrySet()) {
            for (var reference :
                    List.of(entry.getValue().sequential(), entry.getValue().concurrent())) {
                var className = reference.substring(0, reference.indexOf('#'));
                var method = reference.substring(reference.indexOf('#') + 1);
                var source =
                        INTEGRATION_TEST_SOURCES.resolve(className.replace('.', '/') + ".java");
                assertThat(source).as(entry.getKey() + " -> " + reference).exists();
                assertThat(Files.readString(source))
                        .as(entry.getKey() + " -> " + reference)
                        .contains("void " + method + "(");
            }
        }
    }

    @Test
    @DisplayName(
            "the M-40 row of the test matrix names every queue and every test of the inventory")
    void matrixRow_matchesTheInventory() throws IOException {
        assertThat(MATRIX).as("run from apps/backend, as Gradle does").exists();
        var row =
                Files.readAllLines(MATRIX).stream()
                        .filter(line -> line.startsWith("| M-40 |"))
                        .findFirst()
                        .orElseThrow();

        for (var entry : INVENTORY.entrySet()) {
            assertThat(row).as("queue").contains(entry.getKey());
            for (var reference :
                    List.of(entry.getValue().sequential(), entry.getValue().concurrent())) {
                var method = reference.substring(reference.indexOf('#') + 1);
                var simpleClass =
                        reference.substring(reference.lastIndexOf('.') + 1, reference.indexOf('#'));
                assertThat(row).as(reference).contains(simpleClass).contains(method);
            }
        }
    }
}
