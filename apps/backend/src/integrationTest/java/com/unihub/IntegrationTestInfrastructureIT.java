package com.unihub;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M-16: integration tests run against the real PostgreSQL/Redis/RabbitMQ containers, never an
 * in-memory database (CLAUDE.md, integration-testing.md §1). Today every IT happens to extend
 * {@link IntegrationTestSupport}; this makes it a rule a new IT cannot quietly break.
 */
class IntegrationTestInfrastructureIT {

    /** Embedded/in-memory engines and the Spring hooks that swap one in for the real datasource. */
    private static final List<String> IN_MEMORY_DRIVERS =
            List.of(
                    "org.h2.Driver",
                    "org.hsqldb.jdbc.JDBCDriver",
                    "org.apache.derby.jdbc.EmbeddedDriver",
                    "org.apache.derby.iapi.jdbc.AutoloadedDriver");

    private static JavaClasses integrationTests;

    @BeforeAll
    static void importIntegrationTestClasses() {
        integrationTests =
                new ClassFileImporter()
                        .withImportOption(location -> location.contains("/integrationTest/"))
                        .importPackages("com.unihub");
    }

    @Test
    @DisplayName("every *IT runs on the shared real containers (extends IntegrationTestSupport)")
    void everyIt_extendsIntegrationTestSupport() {
        classes()
                .that()
                .haveSimpleNameEndingWith("IT")
                .and()
                .doNotHaveSimpleName(IntegrationTestInfrastructureIT.class.getSimpleName())
                .should()
                .beAssignableTo(IntegrationTestSupport.class)
                .because("integration tests use real Postgres/Redis/RabbitMQ (M-16)")
                .check(integrationTests);
    }

    @Test
    @DisplayName("no integration test touches an in-memory database or swaps the datasource")
    void noInMemoryDatabase() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.h2..", "org.hsqldb..", "org.apache.derby..")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName(
                        "org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName(
                        "org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder")
                .because(
                        "integration tests use real Postgres, never an in-memory substitute (M-16)")
                .check(integrationTests);
    }

    @Test
    @DisplayName("no in-memory JDBC driver is on the integration-test classpath")
    void noInMemoryDriverOnClasspath() {
        for (var driver : IN_MEMORY_DRIVERS) {
            assertThat(isLoadable(driver)).as(driver + " must not be on the classpath").isFalse();
        }
    }

    @Test
    @DisplayName("the shared datasource is a real PostgreSQL container")
    void sharedDatasource_isPostgresContainer() {
        assertThat(IntegrationTestSupportAccess.jdbcUrl()).startsWith("jdbc:postgresql://");
        assertThat(IntegrationTestSupportAccess.postgresRunning()).isTrue();
    }

    private static boolean isLoadable(String className) {
        try {
            Class.forName(className, false, IntegrationTestInfrastructureIT.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** Reads the protected containers of {@link IntegrationTestSupport} without becoming an IT. */
    private static final class IntegrationTestSupportAccess extends IntegrationTestSupport {
        static String jdbcUrl() {
            return POSTGRES.getJdbcUrl();
        }

        static boolean postgresRunning() {
            return POSTGRES.isRunning();
        }
    }
}
