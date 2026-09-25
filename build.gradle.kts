plugins {
    java
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
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
