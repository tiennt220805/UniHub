package com.unihub.payment;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AC16.6 (checklist D16): {@code metadata.payment_id} is written to the gateway for an operator who
 * is investigating, and nothing ever resolves a payment from it. A payment is found by the {@code
 * gateway_transaction_id} stored in our own database, never by a label read back from the gateway:
 * a lookup by metadata would be a lookup by search, and the gateway warns against search for
 * anything that must be consistent (payment.md §6).
 */
@AnalyzeClasses(packages = "com.unihub", importOptions = ImportOption.DoNotIncludeTests.class)
class PaymentMetadataUsageTest {
    private static final Path MAIN = Path.of("src", "main", "java");

    @ArchTest
    static final ArchRule no_code_reads_the_metadata_of_a_gateway_object =
            noClasses()
                    .should()
                    .callMethodWhere(target(nameMatching("getMetadata")))
                    .because(
                            "metadata.payment_id is for an operator; resolving a payment from it is"
                                    + " forbidden (checklist D16, AC16.6)");

    @Test
    @DisplayName("the payment_id label is spelled in one place only: where the adapter writes it")
    void theLabelIsWrittenByTheGatewayAdapterOnly() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            var mentioning =
                    files.filter(f -> f.toString().endsWith(".java"))
                            .filter(f -> contains(f, "\"payment_id\""))
                            .map(f -> f.getFileName().toString())
                            .toList();

            assertThat(mentioning).containsExactly("StripePaymentGateway.java");
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
