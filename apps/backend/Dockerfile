# apps/backend/Dockerfile — build context: apps/backend (deployment-and-cicd.md §1; Phase 7 checklist C45, D84)
#
#   docker build -t unihub-backend:dev apps/backend
#
# Base images are pinned by digest: the digest of each tag's multi-platform index (linux/amd64 and
# linux/arm64), so one build file serves CI (amd64) and the Minikube machine (arm64). The tag in front of
# the digest is only there to say what it is; Docker resolves the digest. To move a pin, re-run
# `docker buildx imagetools inspect <tag>` and confirm both platforms are still listed.

# ---- Builder stage: runs on the build machine's own architecture, so Gradle never runs under QEMU ----
# eclipse-temurin:21-jdk-jammy
FROM --platform=$BUILDPLATFORM eclipse-temurin@sha256:4b1a74fbffbe6b4e7b6f0171a472478282fb463790a51edf10e465d578f22b69 AS builder
WORKDIR /workspace

# Dependency layer: re-run only when the build scripts or the Gradle wrapper change, never on a source edit.
COPY gradlew ./
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts ./
# Not `./gradlew dependencies`: that resolves the dependency graph but downloads no jar (checked 2026-11-05:
# the `bootJar --offline` below then failed on a missing commons-lang3 jar).
RUN ./gradlew --no-daemon --init-script gradle/resolve-main-dependencies.init.gradle resolveMainDependencies

# Source layer: main sources only, so an edit under src/test or src/integrationTest never invalidates it.
COPY src/main src/main
# --offline: if the dependency layer above were incomplete, this fails loudly instead of quietly
# downloading what it is missing (the cache would then no longer mean what this file says it means).
RUN ./gradlew bootJar --offline --no-daemon -x test

# ---- Runtime stage: one per target architecture ----
# eclipse-temurin:21-jre-jammy
FROM eclipse-temurin@sha256:1eac8b8fee11ca7d6725a48bc5d41992ed1561504ebae25490d2d062847e8269 AS runtime

# Non-root, with a numeric id: a Pod with runAsNonRoot refuses a container whose USER is a name it cannot
# resolve to a number.
RUN groupadd --system --gid 10001 unihub \
 && useradd --system --uid 10001 --gid unihub --no-create-home --shell /usr/sbin/nologin unihub
WORKDIR /app
# Not build/libs/*.jar: that also matches the "-plain" jar Gradle writes beside the boot jar.
COPY --from=builder /workspace/build/libs/unihub-*-SNAPSHOT.jar app.jar
USER 10001:10001

# cgroup-aware sizing: the heap follows the container's own memory limit (resources.limits.memory), not the
# node's RAM. 75 % leaves room for metaspace, thread stacks and direct buffers inside the same limit.
ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0"

# 8080: the API. 8081: the management port (Actuator health and Prometheus), a separate listener (Phase 6 D63).
EXPOSE 8080 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
