// The Flyway Gradle plugin's tasks (flywayMigrate, flywayInfo, ...) run against the plugin's own
// buildscript classloader, not the project's compile/runtime classpath — the JDBC driver and the
// Postgres-specific Flyway module must be declared here, never in the regular `dependencies {}` block
// below, or flywayMigrate fails with "No Flyway database plugin found to handle jdbc:...". This does NOT
// put a Postgres driver on the application's own runtime classpath (Mục 2's concern, not this task's).
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.postgresql:postgresql:42.7.13")
        classpath("org.flywaydb:flyway-database-postgresql:13.8.0")
    }
}

plugins {
    java
    checkstyle
    jacoco
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
    // Pinned explicitly (repo convention, see other plugins above). 8.10.2 is the latest stable as of
    // this writing; requires Gradle >=7.3 and Java >=17, both satisfied by this project (Gradle 8.14 / Java 21).
    id("com.diffplug.spotless") version "8.10.2"
    // Pinned explicitly. 13.8.0 is the latest stable as of this writing and the first line to require
    // Java 21 (this project's toolchain) as its minimum — earlier 10.x/11.x/12.x lines only required 17.
    // Supports PostgreSQL 12-18, covering the postgres:16-alpine this project runs (docker-compose.yml).
    // NOTE: deployment-and-cicd.md §4's Kubernetes Migration Job pins a separate artifact, the Flyway CLI
    // Docker image `flyway/flyway:10-alpine` (floating tag on the 10.x line) — that document doesn't fix
    // a Gradle-plugin version for local dev, so this pin doesn't contradict it, but the two now run
    // different Flyway major versions against the same migration files. Both understand the same
    // versioned-SQL-file format, so this is not expected to cause a migration to behave differently
    // between local dev and the K8s Job, but flagging it here since nothing in the corpus reconciled the
    // two numbers explicitly.
    id("org.flywaydb.flyway") version "13.8.0"
    // Pinned explicitly. 1.9.0 is the latest stable as of this writing. Registers `generateOpenApiDocs`,
    // which boots the app in a forked JVM (`forkedSpringBootRun`) and fetches its live `/v3/api-docs` —
    // contract-testing.md §2's local-repro step 1, standing alone, deliberately NOT wired into `check`
    // (same section; it reads live controller annotations, not the hand-written contract in
    // packages/api-contract, so it has nothing to do with `bundleApiContract` above).
    id("org.springdoc.openapi-gradle-plugin") version "1.9.0"
}

// Reuses docker-compose.yml's own env vars and dev-only defaults (env.example) so `./gradlew
// flywayMigrate` works with zero extra setup against the Postgres `docker compose up -d` starts — same
// zero-setup philosophy as docker-compose.yml itself. Host is 127.0.0.1, matching docker-compose.yml's
// explicit `127.0.0.1:<port>:5432` binding (no POSTGRES_HOST var exists in env.example to read instead).
flyway {
    url = "jdbc:postgresql://127.0.0.1:${System.getenv("POSTGRES_PORT") ?: "5432"}/${System.getenv("POSTGRES_DB") ?: "unihub"}"
    user = System.getenv("POSTGRES_USER") ?: "unihub"
    password = System.getenv("POSTGRES_PASSWORD") ?: "unihub_dev_only"
}

// contract-testing.md §2's local-repro step 1 (`cp build/openapi.json /tmp/generated.json`) hardcodes
// this exact path — outputDir/outputFileName pinned explicitly here so that path never drifts, even
// though both already match the plugin's own defaults. `generateOpenApiDocs` is standalone (not wired
// into `check`, see plugin block above), so no other task depends on this config.
openApi {
    outputDir.set(layout.buildDirectory.get().asFile)
    outputFileName.set("openapi.json")
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
// It also sees `test`'s own compiled output, not just `main`'s: shared fixture builders
// (guidelines/test-data-management.md §1) are expected to live in src/test/java rather than be
// duplicated per source set, and an `*IT` needs to build the same fixtures a `*Test` does.
sourceSets {
    create("integrationTest") {
        compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
        runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
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

    // Spring MVC — needed only so `generateOpenApiDocs` (Mục 0.8) has a live `/v3/api-docs` endpoint to
    // fetch from a booted app. Version managed by the Boot BOM. No controller/business logic added here
    // (Mục 1/2's job); the app currently boots with zero @RestController beans.
    implementation("org.springframework.boot:spring-boot-starter-web")
    // Bean Validation (Hibernate Validator) — backend-coding-standards.md §18: request shape is validated
    // in `web` with constraint annotations. Without it `@Min`/`@Max` on a controller parameter compile but
    // are silently never evaluated (spring-boot-starter-web does not pull a validator in).
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Spring Security + JWT verification (auth-and-identity.md §3): the resource server decodes the auth
    // JWT via nimbus-jose-jwt (pulled in transitively, the JOSE library the design names). Verification
    // and, through Spring Security's NimbusJwtEncoder, signing: the login endpoint issues the HS256 token.
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    // Hibernate/JPA for the Tier A persistence adapters (backend-coding-standards.md §9.1/§9.2: a separate
    // JPA entity + Spring Data interface + mapper per aggregate; Workshop is the first). JdbcTemplate below
    // stays for read-model queries and Tier B adapters. Schema generation stays off (application.yml).
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    // JdbcTemplate — the adapter style backend-coding-standards.md §21 item 4 already establishes for
    // simple read/insert adapters; chosen over spring-boot-starter-data-jpa (not otherwise needed yet,
    // and architecture.md §6.2 / INV-15 keep schema generation out of the ORM's hands regardless).
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    // Spring AMQP: the RabbitTemplate the outbox relay publishes through and the @RabbitListener
    // infrastructure for module consumers (messaging.md §1, backend-coding-standards.md §8). Nothing
    // connects to the broker until the first publish or a declared listener, so the app still boots
    // with no broker (contract-diff boots it without any dependency).
    implementation("org.springframework.boot:spring-boot-starter-amqp")
    // Micrometer's API only (version from the Boot BOM): LoggingAuditTrail counts the audit entries it
    // could not record (observability.md §4, unihub_audit_publish_failures_total). No registry and no
    // actuator yet; the counter attaches to the global registry when the observability stack lands.
    implementation("io.micrometer:micrometer-core")
    // Redis client (distributed-coordination.md §1: Redisson, single-server mode). Pinned on the 3.x line that
    // matches Boot 3.3 (4.x targets newer Boot/Netty). Plain `redisson`, not the Spring Boot starter: the
    // client is configured from unihub.redis.* in RedisConfig, and created only when an address is set, so
    // the app still boots with no Redis (contract-diff boots it without any dependency).
    implementation("org.redisson:redisson:3.45.1")
    // Stripe Java SDK (payment.md §3, checklist D16): used only inside payment.infrastructure.out.client.
    // 33.4.2 is the newest stable release older than two weeks when it was chosen. The client is built
    // per instance with an explicit HTTP client, timeouts and zero SDK retries, never through the static
    // Stripe.apiKey.
    implementation("com.stripe:stripe-java:33.4.2")
    // Resilience4j (payment.md §2, checklist 3.7): the shared `paymentGateway` circuit breaker plus the
    // per-call-site Retry/TimeLimiter, used only inside payment.infrastructure.out.client. The plain
    // libraries, not the Boot starter (configured from unihub.payment.breaker.*, as Redisson is); the
    // micrometer module publishes resilience4j_circuitbreaker_state, which observability.md §6 alerts on.
    // 2.4.0 is the newest release and older than two weeks when it was chosen.
    implementation("io.github.resilience4j:resilience4j-circuitbreaker:2.4.0")
    implementation("io.github.resilience4j:resilience4j-retry:2.4.0")
    implementation("io.github.resilience4j:resilience4j-timelimiter:2.4.0")
    implementation("io.github.resilience4j:resilience4j-micrometer:2.4.0")
    // Runtime JDBC driver for the application itself. The buildscript{} block above declares the same
    // artifact separately for the Flyway Gradle plugin's own classloader (see that block's comment) —
    // the two are independent dependency graphs, so this is not a duplicate.
    runtimeOnly("org.postgresql:postgresql:42.7.13")
    // Pinned explicitly to 2.6.0, NOT the newer 2.8.6 latest-stable: springdoc 2.7.0+ switched to
    // Spring Boot 3.4's webjars-locator-lite (references org.springframework.web.servlet.resource.
    // LiteWebJarsResourceResolver, a Spring Framework 6.2+ class) and fails to boot on this project's
    // pinned Spring Boot 3.3.5 (Spring Framework 6.1.14) with `NoClassDefFoundError` on that class,
    // verified while probing this task. 2.6.0 is the last release on the Boot-3.3-compatible line.
    // Supplies the `/v3/api-docs` endpoint that `generateOpenApiDocs` fetches; the `-ui` artifact also
    // bundles Swagger UI, unused here but is the conventional single starter for this rather than
    // pulling the non-UI core artifact separately.
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // ArchUnit 1.x (latest stable) — powers com.unihub.ArchitectureTest, the first test of Phase 1 (M-12).
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.0")
    // The Payment Gateway stand-in for integration tests (mocking-guidelines.md §2): the standalone jar
    // shades Jetty, so it cannot clash with Spring Boot's own. Latest stable 3.x (4.x is still beta).
    testImplementation("org.wiremock:wiremock-standalone:3.13.2")

    // Pinned explicitly. 2.0.5 is the latest stable as of this writing (Java 21 / Gradle 8.14 compatible;
    // requires JUnit 5, which this project already uses exclusively). Testcontainers 2.x renamed every
    // module artifact except the BOM and the core `testcontainers` artifact to a `testcontainers-` prefix.
    // (1.21.4 was tried during troubleshooting on 2026-09-28 and hit the identical docker-java/Docker
    // Desktop 4.55 failure that src/integrationTest/resources/docker-java.properties works around —
    // confirms it isn't an API-negotiation regression that 2.x fixes, so there is no reason to trade the
    // latest-stable pin away for it.)
    //
    // Versions are repeated explicitly on each dependency below, not left to the BOM alone: Spring Boot's
    // io.spring.dependency-management plugin (spring-boot-dependencies) manages its own, much older
    // org.testcontainers:testcontainers version (1.19.8, still on JUnit 4) globally across the whole
    // project, and that managed constraint otherwise wins over a platform()-imported BOM's suggested
    // version — verified via `./gradlew dependencies --configuration integrationTestRuntimeClasspath`,
    // which showed `testcontainers:2.0.5 -> 1.19.8 (c)` before this fix. An explicit version on the
    // dependency declaration itself takes precedence over Spring's managed constraint.
    integrationTestImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    integrationTestImplementation("org.testcontainers:testcontainers:2.0.5")
    integrationTestImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.5")
    // Module-specific containers: PostgreSQLContainer, RabbitMQContainer, MinIOContainer. Redis uses the
    // core GenericContainer above directly (test-levels/integration-testing.md §2), so no Redis module.
    integrationTestImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
    integrationTestImplementation("org.testcontainers:testcontainers-rabbitmq:2.0.5")
    integrationTestImplementation("org.testcontainers:testcontainers-minio:2.0.5")

    // Flyway Java API, called directly from IntegrationTestSupport (not Spring Boot's
    // spring.flyway.enabled autoconfig — INV-15, migration never runs from inside the application
    // itself, test context included). Same version as the Gradle plugin (buildscript{} block above) for
    // one less number to reconcile locally; deployment-and-cicd.md §4's K8s Job is a separate concern.
    integrationTestImplementation("org.flywaydb:flyway-core:13.8.0")
    integrationTestImplementation("org.flywaydb:flyway-database-postgresql:13.8.0")
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

// Validates and bundles packages/api-contract/openapi.yaml (multi-file, $ref-linked across schemas/
// and paths/) into one JSON file via `redocly bundle` — the same tool/command
// docs/testing/test-levels/contract-testing.md §2 uses for local contract-diff reproduction. A broken
// $ref or invalid spec fails `redocly bundle` with a non-zero exit, which fails this task, catching the
// error here instead of only downstream in the Mục 1 contract-diff job. This does NOT generate any Java
// DTO/server interface — backend-coding-standards.md §10/§14.2 requires DTOs to be hand-written from the
// schema names, not codegen'd; that's a separate, deliberately-not-done thing, not this task's job.
val bundleApiContract = tasks.register<Exec>("bundleApiContract") {
    description = "Validates and bundles packages/api-contract/openapi.yaml into build/api-contract/bundled.json."
    group = "verification"

    // Declared explicitly so Gradle's up-to-date check reruns the bundle whenever any source file
    // under packages/api-contract changes, not just when build/api-contract/bundled.json is missing.
    inputs.dir(rootDir.resolve("../../packages/api-contract")).skipWhenEmpty(false)
    val outputFile = layout.buildDirectory.file("api-contract/bundled.json")
    outputs.file(outputFile)
    doFirst {
        outputFile.get().asFile.parentFile.mkdirs()
    }

    // Windows can't exec a .cmd shim (npx.cmd) directly via ProcessBuilder without going through cmd.exe;
    // other OSes run npx directly, same as the reference command in contract-testing.md §2.
    val npxArgs = listOf(
        "npx", "--yes", "@redocly/cli", "bundle",
        "../../packages/api-contract/openapi.yaml",
        "-o", outputFile.get().asFile.absolutePath,
    )
    commandLine(
        if (org.gradle.internal.os.OperatingSystem.current().isWindows) {
            listOf("cmd", "/c") + npxArgs
        } else {
            npxArgs
        },
    )
}

tasks.named("check") {
    dependsOn(
        tasks.named("spotlessCheck"),
        tasks.named("checkstyleMain"),
        tasks.named("jacocoTestCoverageVerification"),
        bundleApiContract,
    )
}
