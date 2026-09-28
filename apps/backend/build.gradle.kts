plugins {
    java
    checkstyle
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

dependencies {
    implementation("org.springframework.boot:spring-boot-starter")
    // spring-tx supplies org.springframework.transaction.annotation.Transactional — the ONLY @Transactional
    // allowed on application use cases (backend-coding-standards.md §3, §19). It is not transitive via
    // spring-boot-starter, yet the verbatim ArchitectureTest (§26.2) references it. Version managed by the Boot BOM.
    implementation("org.springframework:spring-tx")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // ArchUnit 1.x (latest stable) — powers com.unihub.ArchitectureTest, the first test of Phase 1 (M-12).
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.0")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.withType<Test> {
    useJUnitPlatform()
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

tasks.named("check") {
    dependsOn(tasks.named("spotlessCheck"), tasks.named("checkstyleMain"))
}
