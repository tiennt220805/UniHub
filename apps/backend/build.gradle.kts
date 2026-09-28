plugins {
    java
    checkstyle
    jacoco
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
    // Pinned explicitly (repo convention, see other plugins above). 8.10.2 is the latest stable as of
    // this writing; requires Gradle >=7.3 and Java >=17, both satisfied by this project (Gradle 8.14 / Java 21).
    id("com.diffplug.spotless") version "8.10.2"
}

group = "com.unihub"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

// `integrationTest` is a distinct Gradle source set (not just a task filtering on file name), per
// test-levels/integration-testing.md §3 / guidelines/coding-standards.md §1 — `src/integrationTest/java`
// mirrors `src/main/java`/`src/test/java`, holds only `*IT.java`, and is Testcontainers-backed. It shares
// `test`'s dependencies (JUnit 5, AssertJ, Mockito via spring-boot-starter-test) plus Testcontainers on top.
sourceSets {
    create("integrationTest") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}

val integrationTestImplementation: Configuration by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    implementation("org.springframework.boot:spring-boot-starter")
    // spring-tx supplies org.springframework.transaction.annotation.Transactional — the ONLY @Transactional
    // allowed on application use cases (backend-coding-standards.md §3, §19). It is not transitive via
    // spring-boot-starter, yet the verbatim ArchitectureTest (§26.2) references it. Version managed by the Boot BOM.
    implementation("org.springframework:spring-tx")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // ArchUnit 1.x (latest stable) — powers com.unihub.ArchitectureTest, the first test of Phase 1 (M-12).
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.0")

    // Pinned explicitly. 2.0.5 is the latest stable as of this writing (Java 21 / Gradle 8.14 compatible;
    // requires JUnit 5, which this project already uses exclusively). Testcontainers 2.x renamed every
    // module artifact except the BOM and the core `testcontainers` artifact to a `testcontainers-` prefix.
    // (1.21.4 was tried during troubleshooting on 2026-09-28 and hit the identical docker-java/Docker
    // Desktop 4.55 failure that src/integrationTest/resources/docker-java.properties works around —
    // confirms it isn't an API-negotiation regression that 2.x fixes, so there is no reason to trade the
    // latest-stable pin away for it.)
    integrationTestImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    integrationTestImplementation("org.testcontainers:testcontainers")
    integrationTestImplementation("org.testcontainers:testcontainers-junit-jupiter")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Unit (`test`) never touches Testcontainers/Docker — belt-and-suspenders alongside the fact that its
// testClassesDirs (src/test/java's output) never contains `*IT` classes to begin with (separate source set).
tasks.test {
    exclude("**/*IT.class")
}

tasks.register<Test>("integrationTest") {
    description = "Runs *IT.java integration tests (Testcontainers-backed: Postgres/Redis/RabbitMQ/MinIO)."
    group = "verification"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    include("**/*IT.class")
    // Independent from `test`/`check` on purpose (guidelines/coding-standards.md §1,
    // test-levels/integration-testing.md §3): `unit-test` and `integration-test` are separate,
    // sequentially-gated CI jobs (`./gradlew test` then `./gradlew integrationTest`), not one Gradle
    // invocation — `check`/`build` must stay fast and never implicitly boot containers.
    shouldRunAfter(tasks.test)
}

// Formatting (Spotless) and semantic/banned-pattern linting (Checkstyle) are two separate concerns
// (config/checkstyle/checkstyle.xml, header comment). Both are part of the `lint` CI job
// (docs/testing/test-levels/unit-testing.md §2) and must be runnable independently of `test`.
spotless {
    java {
        target("src/**/*.java")
        // Pinned explicitly. 1.36.1 is the latest stable as of this writing and supports Java 21 syntax
        // (sealed types, records, pattern-matching switch); it requires running on JDK 21+, which this
        // project's toolchain (java {} block above) and JAVA_HOME already satisfy.
        googleJavaFormat("1.36.1").aosp()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

checkstyle {
    // Pinned explicitly. 10.26.1 is the latest release on the 10.x line (the line still current when this
    // was pinned; Checkstyle later jumped straight from 10.x to a 13.x versioning scheme) and parses Java 21
    // syntax (sealed types, records, pattern-matching switch) via its TreeWalker.
    toolVersion = "10.26.1"
    // config/checkstyle/checkstyle.xml is the Gradle checkstyle plugin's own default path — kept as-is so
    // no explicit configFile is needed; matches the path backend-coding-standards.md §26 assumes.
    isIgnoreFailures = false
    maxWarnings = 0
}

// INV-01 (role-name-ban) applies to src/main only (backend-coding-standards.md §26): tests may legitimately
// contain "ROLE_..." string literals as negative-path test data (e.g. asserting such a check is rejected).
// Checkstyle's own formatting-adjacent rules are Spotless's job, not Checkstyle's — see checkstyle.xml header.
tasks.named("checkstyleTest") {
    enabled = false
}

// Same reasoning as checkstyleTest above: *IT.java may also legitimately hold "ROLE_..." as negative-path
// test data. The checkstyle plugin auto-registers this task (and auto-wires it into `check`) the moment
// the integrationTest source set exists, so it must be explicitly turned off, same as checkstyleTest.
tasks.named("checkstyleIntegrationTest") {
    enabled = false
}

jacoco {
    // Pinned explicitly. 0.8.15 is the latest stable as of this writing; official Java 21 support has
    // been in place since 0.8.11 and carries forward (JaCoCo instruments bytecode, not source, so newer
    // releases stay backward compatible with the class files this toolchain produces).
    toolVersion = "0.8.15"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.test {
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    // Explicit rather than relying on the plugin's default wiring: only `test` (Unit) execution data
    // feeds this gate. When `integrationTest` (Testcontainers) is added later, it must never be allowed
    // to dilute or inflate this number (unit-testing.md §2/§3 keep coverage and infra-level proof separate).
    executionData(tasks.test.get())
    violationRules {
        rule {
            element = "PACKAGE"
            // Core business-logic packages only, per backend-coding-standards.md §27 #2 (this repo's
            // authority for this gate's scope):
            //   application.usecase — every use case, and every module's api-port implementation
            //   domain.*            — domain.model and domain.event of Tier A modules
            // JaCoCo's '*' also matches '.', and with element = "PACKAGE" each pattern is matched against
            // the full package name — so "usecase" has no trailing ".*" (it has no sub-packages; a trailing
            // ".*" would match nothing and the gate would pass vacuously).
            includes = listOf("com.unihub.*.application.usecase", "com.unihub.*.domain.*")
            // Trivial types inside the included trees: exception classes carry no branching logic.
            excludes = listOf("com.unihub.*.domain.exception")
            limit {
                counter = "LINE"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.named("check") {
    dependsOn(
        tasks.named("spotlessCheck"),
        tasks.named("checkstyleMain"),
        tasks.named("jacocoTestCoverageVerification"),
    )
}
