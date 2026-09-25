# Unit Testing — UniHub Test Levels

This file is the physical-level completion of `../strategy/test-strategy.md` §3's Unit row (JUnit 5 + Mockito, ~65% target share, "Small" resource size) — what exactly counts as Unit scope in this codebase, how it's wired into CI, and how the ≥80% coverage gate (`test-strategy.md` §4) is actually measured rather than only promised in prose. Nothing here overrides `test-strategy.md` — where this file and that one appear to disagree, `test-strategy.md` is the authority and this file has a bug.

**Out of scope, and where it actually lives:** when to mock vs. use real infrastructure (`../guidelines/mocking-guidelines.md`); test naming/structure/`@ParameterizedTest` conventions (`../guidelines/coding-standards.md`); fixture/builder construction (`../guidelines/test-data-management.md`); which INV rows require Integration instead (`../strategy/test-strategy.md` §2).

---

## Table of Contents

- [1. Scope — What Counts as "Unit"](#1-scope-what-counts-as-unit)
- [2. Gradle Wiring & CI Gate](#2-gradle-wiring-ci-gate)
- [3. Coverage Measurement & the 80% Gate](#3-coverage-measurement-the-80-gate)
- [4. What Never Belongs at Unit Level](#4-what-never-belongs-at-unit-level)
- [5. Reference Table](#5-reference-table)

---

## 1. Scope — What Counts as "Unit"

**Rationale:** `test-strategy.md` §4 already defines "core business-logic service" as the service-layer/domain logic narrated in each module's `technical-design/modules/*.md` story, excluding DTOs, config classes, the generated `packages/api-contract` client, and mappers. This section adds nothing new to that definition — it exists only to name the one Non-Negotiable Invariant that is *provable* at this level, since every other INV row in `test-strategy.md` §2 is Integration-level, deployment-level or a static check.

**Model:** Unit scope = the in-process business logic each module owns, tested with its collaborators mocked (`../guidelines/mocking-guidelines.md` §2) — seat-reservation decision logic, payment orchestration branching, permission-evaluation logic, reconciliation *rules* (as opposed to the SQL that executes them).

**The one INV provable here:** INV-12's on-device half — JOSE signature verification (`checkin.md` §6) is pure cryptographic logic over a byte payload: no database, no network, no RabbitMQ, "never calls the network" by the source document's own words. It is the only Non-Negotiable Invariant in `test-strategy.md` §2 whose correctness proof belongs at Unit level rather than Integration — every other row needs real infrastructure (Postgres/Redis/RabbitMQ/MinIO, or the full topology for INV-16/INV-20) or is a static check (INV-18) to mean anything. INV-12's *other* half (server-side reconciliation, `checkin.md` §7) is Integration-level (`../test-levels/integration-testing.md`) — the same INV ID spans both files because the invariant itself spans an on-device/server split, not because either file is wrong.

**Guarantees:** a Unit test asserting on a mocked repository's `save()` call was invoked is not proof of anything `test-strategy.md` §1's objectives care about — Unit tests here assert on the *decision* a service makes given inputs (accept/reject, resolved/failed, transition allowed/rejected), state-based per `../guidelines/coding-standards.md` §3, never on which internal method fired.

---

## 2. Gradle Wiring & CI Gate

**Rationale:** `deployment-and-cicd.md` §5's real GitHub Actions workflow is the authoritative wiring — this section cites it rather than re-deriving a competing description.

**Model:**

| Job | Gated by | Command |
|---|---|---|
| `unit-test` | `lint` success (Checkstyle + Spotless) | `./gradlew test --no-daemon` |

`unit-test` gates `integration-test` in turn — a Unit test failure blocks the far more expensive Testcontainers job from ever starting, per `deployment-and-cicd.md`'s CI Job Structuring ADR's explicit reasoning ("ordering it last... means a[n expensive job] never wastes the time of a run that was already going to fail").

**Guarantees:** `./gradlew test` never boots Testcontainers and never talks to a real Postgres/Redis/RabbitMQ/MinIO (`../guidelines/coding-standards.md` §1's own guarantee, restated here as the CI-level consequence of it) — a `unit-test` job failure is always attributable to business logic, never to Docker/infrastructure flakiness on the runner.

---

## 3. Coverage Measurement & the 80% Gate

**Rationale:** `test-strategy.md` §4 states the ≥80% core-business-logic coverage requirement as a blocking gate on this stage. This section originally documented a discovered gap — the real `unit-test` job ran only `./gradlew test --no-daemon`, no coverage tool, no verification step, so the gate was promised in `test-strategy.md` but not enforced anywhere. **That gap is now closed**: `deployment-and-cicd.md`'s Coverage Verification Mechanism ADR and its `unit-test` job (§5 there) wire in the fix below as an actual CI step, not just a proposal.

**Model:** `org.jacoco` Gradle plugin (JaCoCo) — the standard Gradle+JUnit5 coverage tool, this document's own concrete tool decision (the same pattern `mocking-guidelines.md` §2 and `coding-standards.md` §3 use for WireMock/AssertJ), now formalized in `deployment-and-cicd.md`'s Coverage Verification Mechanism ADR:

```gradle
jacocoTestCoverageVerification {
    violationRules {
        rule {
            element = 'PACKAGE'
            // Core business-logic packages only, per docs/guidelines/backend-coding-standards.md §2:
            //   application.usecase — every use case, and every module's api-port implementation
            //   domain.*            — domain.model and domain.event of Tier A modules
            // JaCoCo's '*' also matches '.', and with element = 'PACKAGE' each pattern is matched against
            // the full package name — so 'usecase' has no trailing '.*' (it has no sub-packages; a trailing
            // '.*' would match nothing and the gate would pass vacuously).
            includes = ['com.unihub.*.application.usecase', 'com.unihub.*.domain.*']
            // Trivial types inside the included trees: exception classes carry no branching logic.
            excludes = ['com.unihub.*.domain.exception']
            limit {
                counter = 'LINE'
                minimum = 0.80
            }
        }
    }
}
check.dependsOn jacocoTestCoverageVerification
```

What is deliberately outside the gate, and why: `command`/`result`/`web.dto` (records, no logic), `web`/`infrastructure` (adapters — translation, proven at Integration level, `backend-coding-standards.md` §26.1), `config` (wiring), and mappers (in `web.mapper` / `infrastructure.out.persistence`, so never inside an included package). None of these need an explicit `excludes` entry, because the `includes` patterns never reach them. Tier B modules (User, Notification) have no `domain` package, so their `application.usecase` package is their entire gated surface. `com.unihub.shared.application.usecase` (the outbox relay) is included by the same pattern.

**Verify on the Phase 1 scaffold:** once the first use case exists, run `./gradlew jacocoTestReport` and confirm the report lists `com.unihub.<module>.application.usecase` as a measured package. A gate that measures zero packages passes silently — that is the failure mode this pattern was corrected for.

**Wired into CI:** `deployment-and-cicd.md` §5's `unit-test` job runs `./gradlew jacocoTestCoverageVerification --no-daemon` as its second step, after `./gradlew test --no-daemon` — the 80% figure is now an enforced Gradle rule the `unit-test` job actually fails on, not only a number `test-strategy.md`'s prose promises.

**Guarantees:** a PR that drops core business-logic coverage below 80% fails `unit-test` directly — the same job `test-strategy.md` §4 already names as the coverage gate, not a separate, easily-forgotten check.

---

## 4. What Never Belongs at Unit Level

**Rationale:** a one-line negative list, stated once, catches the most common way a "Unit" test quietly becomes an Integration test in disguise.

**Model:**

| Never in a `*Test.java` class | Belongs instead in |
|---|---|
| `@Testcontainers` / any real Postgres, Redis, RabbitMQ, or MinIO container | `../test-levels/integration-testing.md` |
| A booted Spring `ApplicationContext` (`@SpringBootTest`) | `../test-levels/integration-testing.md` — a Unit test constructs the class under test directly or via plain Mockito, no context |
| A real HTTP call to any external provider, even via WireMock | Also Integration — WireMock stands in for the Integration level's HTTP boundary (`../guidelines/mocking-guidelines.md` §2), a Unit test mocks the Java client interface instead |
| A concurrency scenario (multiple threads racing a shared row) | `../test-levels/integration-testing.md`, `../guidelines/coding-standards.md` §4 — nothing to race against without a real, shared datastore |

**Guarantees:** a reviewer who sees `@Testcontainers`, `@SpringBootTest`, or a `CountDownLatch` in a file ending `Test.java` (not `IT.java`) has found a misnamed file, per `../guidelines/coding-standards.md` §1 — not a new kind of Unit test.

---

## 5. Reference Table

| Item | Value | Section |
|---|---|---|
| Gradle task | `./gradlew test` | §2 |
| CI job | `unit-test`, gated by `lint` | §2 |
| Coverage tool | JaCoCo (`jacocoTestCoverageVerification`, 80% line minimum per package, `com.unihub.*.application.usecase` + `com.unihub.*.domain.*`) | §3 |
| Coverage gate status | Wired into `deployment-and-cicd.md` §5's `unit-test` job (Coverage Verification Mechanism ADR) | §3 |
| INV rows provable here | INV-12 (on-device half only) | §1 |
| Never present | Testcontainers, `@SpringBootTest`, real HTTP, concurrency constructs | §4 |
