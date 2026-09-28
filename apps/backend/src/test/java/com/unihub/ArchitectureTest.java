package com.unihub;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

@AnalyzeClasses(packages = "com.unihub", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final List<String> MODULES =
            List.of("user", "workshop", "registration", "payment", "checkin", "notification");

    /** Any business module. Never matches com.unihub.shared. */
    private static final String ANY =
            "com.unihub.[user|workshop|registration|payment|checkin|notification]";

    /**
     * Libraries only infrastructure.out (and infrastructure.config / shared.infrastructure) may use
     * (§9.3). Only the publishing side of Spring AMQP is listed: listeners in infrastructure.in
     * legitimately use @RabbitListener (org.springframework.amqp.rabbit.annotation).
     */
    private static final String[] DRIVEN_LIBRARIES = {
        "jakarta.persistence..", "org.hibernate..", "org.springframework.data..",
                "org.springframework.jdbc..",
        "org.springframework.amqp.rabbit.core..", "org.redisson..", "software.amazon..",
                "com.stripe..",
        "io.github.resilience4j..", "com.nimbusds..", "org.jose4j.."
    };

    // ---------- §1 Module boundary ----------

    @ArchTest
    static void modules_meet_only_through_api(JavaClasses classes) {
        for (String module : MODULES) {
            String own = "com.unihub." + module + "..";
            noClasses()
                    .that()
                    .resideInAPackage(own)
                    .should()
                    .dependOnClassesThat(
                            resideInAPackage(ANY + "..")
                                    .and(not(resideInAPackage(own)))
                                    .and(not(resideInAPackage(ANY + ".api.."))))
                    .as(
                            "module '"
                                    + module
                                    + "' imports other modules only through their api package (§1)")
                    .check(classes);
        }
    }

    @ArchTest
    static final ArchRule shared_is_independent_of_modules =
            noClasses()
                    .that()
                    .resideInAPackage("com.unihub.shared..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage(ANY + "..")
                    .as("shared never depends on a business module (§13)");

    // ---------- §3 Dependency rule, one rule per row ----------

    @ArchTest
    static final ArchRule domain_is_plain_java =
            classes()
                    .that()
                    .resideInAnyPackage(ANY + ".domain..", "com.unihub.shared.domain..")
                    .should()
                    .onlyDependOnClassesThat()
                    .resideInAnyPackage("java..", ANY + ".domain..", "com.unihub.shared.domain..")
                    .as("domain is plain Java (§3, §5)");

    @ArchTest
    static final ArchRule application_stays_framework_free =
            noClasses()
                    .that()
                    .resideInAPackage(ANY + ".application..")
                    .should()
                    .dependOnClassesThat(
                            resideInAnyPackage(
                                            ANY + ".web..",
                                            ANY + ".infrastructure..",
                                            "com.unihub.shared.web..",
                                            "com.unihub.shared.infrastructure..")
                                    .or(resideInAnyPackage(DRIVEN_LIBRARIES))
                                    .or(resideInAPackage("com.fasterxml.jackson.."))
                                    .or(
                                            resideInAPackage("org.springframework..")
                                                    .and(
                                                            not(
                                                                    resideInAnyPackage(
                                                                            "org.springframework.stereotype..",
                                                                            "org.springframework.transaction.annotation..")))))
                    .as("application uses only its Spring allow-list (§3)");

    @ArchTest
    static final ArchRule api_is_a_pure_contract =
            classes()
                    .that()
                    .resideInAPackage(ANY + ".api..")
                    .should()
                    .onlyDependOnClassesThat()
                    .resideInAnyPackage(
                            "java..",
                            ANY + ".api..",
                            "com.unihub.shared.domain..",
                            "com.unihub.shared.event..")
                    .as("api depends only on JDK, shared.domain and shared.event (§1, §3)");

    @ArchTest
    static final ArchRule web_sees_only_port_in_command_result =
            noClasses()
                    .that()
                    .resideInAPackage(ANY + ".web..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            ANY + ".domain..",
                            ANY + ".application.port.out..",
                            ANY + ".application.usecase..",
                            ANY + ".application.event..",
                            ANY + ".infrastructure..",
                            "com.unihub.shared.infrastructure..")
                    .as("web depends on port.in, command and result only (§3, §10)");

    @ArchTest
    static final ArchRule driving_adapters_call_only_port_in =
            noClasses()
                    .that()
                    .resideInAPackage(ANY + ".infrastructure.in..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            ANY + ".domain.model..",
                            ANY + ".application.port.out..",
                            ANY + ".application.usecase..",
                            ANY + ".infrastructure.out..",
                            ANY + ".web..")
                    .orShould()
                    .dependOnClassesThat()
                    .resideInAnyPackage(DRIVEN_LIBRARIES)
                    .as("listeners and schedulers call port.in only (§3, §8.5, §9.4)");

    @ArchTest
    static final ArchRule driven_adapters_never_drive =
            noClasses()
                    .that()
                    .resideInAPackage(ANY + ".infrastructure.out..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            ANY + ".application.port.in..", ANY + ".application.usecase..",
                            ANY + ".infrastructure.in..", ANY + ".web..")
                    .as("driven adapters implement port.out and never call use cases (§3, §9)");

    @ArchTest
    static final ArchRule driven_libraries_only_in_infrastructure_out =
            noClasses()
                    .that()
                    .resideOutsideOfPackages(
                            ANY + ".infrastructure.out..",
                            ANY + ".infrastructure.config..",
                            "com.unihub.shared.infrastructure..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(DRIVEN_LIBRARIES)
                    .as(
                            "persistence, messaging, Redis, cloud and crypto libraries only in infrastructure.out (§9.3)");

    // ---------- §19 Transactions ----------

    // §26.2 writes this as one rule ending in
    // `.orShould().containAnyMethodsThat(annotatedWith(...))`, but
    // containAnyMethodsThat exists only on ArchUnit's `.that()` (predicate) side, never on
    // `.should()`, so
    // that exact form does not compile. Split into a class-level and a method-level rule to keep
    // the full
    // §19.1 intent: no @Transactional on controllers, listeners, schedulers or adapters — at class
    // OR method level.
    @ArchTest
    static final ArchRule transactional_only_in_application =
            noClasses()
                    .that()
                    .resideInAnyPackage(
                            ANY + ".web..",
                            ANY + ".infrastructure..",
                            ANY + ".domain..",
                            ANY + ".api..")
                    .should()
                    .beAnnotatedWith(Transactional.class)
                    .as(
                            "@Transactional only on application use cases — no class-level @Transactional here (§19.1)");

    @ArchTest
    static final ArchRule transactional_methods_only_in_application =
            noMethods()
                    .that()
                    .areDeclaredInClassesThat()
                    .resideInAnyPackage(
                            ANY + ".web..",
                            ANY + ".infrastructure..",
                            ANY + ".domain..",
                            ANY + ".api..")
                    .should()
                    .beAnnotatedWith(Transactional.class)
                    .as(
                            "@Transactional only on application use cases — no method-level @Transactional here (§19.1)");

    @ArchTest
    static final ArchRule no_jakarta_transactional =
            noClasses()
                    .should()
                    .dependOnClassesThat()
                    .haveFullyQualifiedName("jakarta.transaction.Transactional")
                    .as("use Spring's @Transactional only (§19)");

    // ---------- §16 Time ----------

    @ArchTest
    static final ArchRule no_system_clock_reads =
            noClasses()
                    .that()
                    .resideOutsideOfPackage("com.unihub.shared.infrastructure..")
                    .should()
                    .callMethod(Instant.class, "now")
                    .orShould()
                    .callMethod(LocalDateTime.class, "now")
                    .orShould()
                    .callMethod(LocalDate.class, "now")
                    .orShould()
                    .callMethod(Clock.class, "systemUTC")
                    .as("time comes from the injected Clock (§16)");

    // ---------- §8 Messaging ----------

    @ArchTest
    static final ArchRule no_reject_and_dont_requeue =
            noClasses()
                    .should()
                    .dependOnClassesThat()
                    .haveFullyQualifiedName(
                            "org.springframework.amqp.AmqpRejectAndDontRequeueException")
                    .as(
                            "use NonRetryableMessageException; reject-without-requeue drops messages here (§8.6)");

    // ---------- §6, §14, §15, §23 Conventions ----------

    @ArchTest
    static final ArchRule no_service_suffix_on_use_cases =
            noClasses()
                    .that()
                    .resideInAPackage(ANY + ".application.usecase..")
                    .should()
                    .haveSimpleNameEndingWith("Service")
                    .as("use case classes are named after their port, never *Service (§14.1)");

    @ArchTest
    static final ArchRule constructor_injection_only = NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

    @ArchTest static final ArchRule slf4j_only = NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;
    @ArchTest static final ArchRule no_system_out = NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
}
