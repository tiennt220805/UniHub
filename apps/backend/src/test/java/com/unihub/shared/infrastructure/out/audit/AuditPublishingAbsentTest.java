package com.unihub.shared.infrastructure.out.audit;

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
 * AC6.4 (checklist D6): in Phase 2 nothing publishes to {@code unihub.system.audit.created}. The
 * audit adapter writes structured log lines. Publishing without a consumer would grow {@code
 * unihub.audit.queue} without bound, and a RabbitMQ memory alarm blocks every publisher, the outbox
 * relay and the registration confirmation included. The publishing adapter and its consumer arrive
 * together in Phase 6 (test-matrix.md §4.5), when this test is replaced.
 */
@AnalyzeClasses(packages = "com.unihub", importOptions = ImportOption.DoNotIncludeTests.class)
class AuditPublishingAbsentTest {
    private static final Path MAIN = Path.of("src", "main", "java");
    private static final String ROUTING_KEY = "unihub.system.audit.created";

    @ArchTest
    static final ArchRule the_logging_adapter_is_the_only_audit_trail =
            classes()
                    .that()
                    .implement(AuditTrail.class)
                    .should()
                    .haveSimpleName("LoggingAuditTrail")
                    .because("Phase 2 audits to the log; RabbitAuditTrail is Phase 6 (M-54)");

    @Test
    @DisplayName("only the topology names the audit routing key, and nothing else refers to it")
    void nothingButTheTopologyMentionsTheAuditRoutingKey() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            var mentioning =
                    files.filter(f -> f.toString().endsWith(".java"))
                            .filter(f -> contains(f, ROUTING_KEY) || contains(f, "AUDIT_CREATED"))
                            .map(f -> f.getFileName().toString())
                            .toList();

            assertThat(mentioning).containsExactly("Topology.java");
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
