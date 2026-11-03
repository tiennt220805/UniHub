package com.unihub.shared.infrastructure.out.messaging;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.unihub.shared.application.port.out.AuditTrail;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Where audit leaves the application (Phase 6 checklist D68, 2.4; replaces Phase 2's {@code
 * AuditPublishingAbsentTest}, which kept anything from publishing to {@code
 * unihub.system.audit.created} until the consumer existed). Audit publishing is one adapter on one
 * connection: a second {@code AuditTrail}, or a second class naming the routing key, would be a
 * second way for an audit message to reach the broker, around the bounded executor and the failure
 * counter that make it fail-open without being silent (INV-14).
 */
@AnalyzeClasses(packages = "com.unihub", importOptions = ImportOption.DoNotIncludeTests.class)
class AuditPublishingRulesTest {
    private static final Path MAIN = Path.of("src", "main", "java");
    private static final String ROUTING_KEY = "unihub.system.audit.created";

    @ArchTest
    static final ArchRule rabbit_audit_trail_is_the_only_audit_trail =
            classes()
                    .that()
                    .implement(AuditTrail.class)
                    .should()
                    .haveSimpleName("RabbitAuditTrail")
                    .because(
                            "one adapter publishes audit: bounded, off the request thread, counted");

    @ArchTest
    static final ArchRule the_amqp_publisher_is_the_only_audit_publisher =
            classes()
                    .that()
                    .implement(AuditPublisher.class)
                    .should()
                    .haveSimpleName("AmqpAuditPublisher")
                    .because("the audit connection is owned in one place");

    @Test
    @DisplayName(
            "only the topology, the audit publisher and nothing else name the audit routing key")
    void onlyTheTopologyAndThePublisherNameTheAuditRoutingKey() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            var mentioning =
                    files.filter(f -> f.toString().endsWith(".java"))
                            .filter(f -> contains(f, ROUTING_KEY) || contains(f, "AUDIT_CREATED"))
                            .map(f -> f.getFileName().toString())
                            .sorted()
                            .toList();

            assertThat(mentioning).containsExactly("AmqpAuditPublisher.java", "Topology.java");
        }
    }

    private static boolean contains(Path file, String text) {
        try {
            return Files.readString(file).contains(text);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
