# Test Coding Standards — UniHub Testing Guidelines

This file is the physical-level completion of `../strategy/test-strategy.md` §3 (Test Pyramid & Sizing) for the question that section doesn't answer: once a test's level and tooling are decided (JUnit 5 + Mockito for Unit, JUnit 5 + Testcontainers for Integration), what does the test code itself actually look like — file layout, naming, structure, assertions? Nothing here overrides `test-strategy.md` — where this file and that one appear to disagree, `test-strategy.md` is the authority and this file has a bug.

**Out of scope, and where it actually lives:** which technique derives a given test's cases (`test-design-techniques.md`); how fixture/seed data is built (`test-data-management.md`); when to mock vs. use real infrastructure (`mocking-guidelines.md`); which test level a piece of logic belongs in (`../test-levels/*.md`).

---

## Table of Contents

- [1. File & Package Naming](#1-file-package-naming)
- [2. Method Naming & `@DisplayName`](#2-method-naming-displayname)
- [3. Structure: Arrange-Act-Assert](#3-structure-arrange-act-assert)
- [4. Parameterized Tests for Catalogued Scenarios](#4-parameterized-tests-for-catalogued-scenarios)
- [5. Reference Table](#5-reference-table)

---

## 1. File & Package Naming

**Rationale:** Java/Gradle convention puts test code under `src/test/java`, mirroring `src/main/java`'s package structure — there is no separate top-level `tests/` directory in this stack (unlike a Node/Python project), so the only remaining question is how to tell a Unit test apart from an Integration test in the same package without a second directory tree.

**Model:**

| Rule | Convention | Why |
|---|---|---|
| Package | `src/test/java/com/unihub/<module>/...` mirrors `src/main/java/com/unihub/<module>/...` exactly | The class under test and its test live at the same package depth — no separate navigation scheme to learn |
| Unit test suffix | `*Test.java` (e.g. `RegistrationServiceTest.java`) | Picked up by Gradle's default `test` task |
| Integration test suffix | `*IT.java` (e.g. `RegistrationConcurrencyIT.java`) | A distinct Gradle source set/task (`integrationTest`) filters on this suffix, so `./gradlew test` (fast, no Testcontainers) and `./gradlew integrationTest` (slower, spins up Postgres/Redis/RabbitMQ) stay independently runnable — the actual `unit-test`/`integration-test` CI jobs already wired to exactly these two Gradle tasks in `../../technical-design/platform/deployment-and-cicd.md` §5, not a convention invented fresh here — matching `../strategy/test-strategy.md` §4's Testcontainers-per-run-per-tier model |
| One test class per production class | `WorkshopService` → `WorkshopServiceTest`; concurrency/cross-cutting scenarios (e.g. the anti-overbooking three-variant table in `test-design-techniques.md` §3.1) get their own named IT class instead of being folded into a single service's test class | A concurrency scenario spans more than one service's write path — forcing it into one class's test file misrepresents what it's actually proving |

**Guarantees:** `./gradlew test` never touches Testcontainers and never talks to a real Postgres/Redis/RabbitMQ — a `*Test` class that needs real infrastructure is misnamed and belongs in `*IT` instead.

---

## 2. Method Naming & `@DisplayName`

**Rationale:** a test name is the first (and sometimes only) thing a reader sees in a red CI run or a failure report — `proposal.md` §17 lists the "Automated Test Suite" itself as a project deliverable, so test names and their rendered output double as documentation of what's actually verified, not just an internal implementation detail.

**Model:** method name in `camelCase` describing behavior and condition, `should`-free (the method name already reads as an assertion, not a plan):

```java
@Test
@DisplayName("rejects a registration when the seat hold has already expired")
void rejectsRegistration_whenHoldExpired() { ... }
```

`@DisplayName` is this document's own concrete decision — not required by `CLAUDE.md`'s tech stack, added because JUnit 5 natively supports it and a full-sentence description renders directly in CI output and any generated test report, which matters for a project whose test suite is itself a deliverable (`proposal.md` §17), not only an internal safety net.

| Rule | Convention |
|---|---|
| Method name shape | `<observedBehavior>_<condition>` (e.g. `acceptsExactlyRemainingSeats_whenConcurrentRequestsExceedCapacity`) |
| Vague names | Never — `testRegistration()`, `test1()`, `worksCorrectly()` name nothing verifiable and are rejected on review |
| One behavior per method | A method verifies one outcome under one condition — see §3 |
| `@DisplayName` | Present on every test method, full sentence, reads naturally as "it ___" |

**Guarantees:** a reviewer reading only test names (or only `@DisplayName` output) can state what the test suite verifies, without opening a single test body.

---

## 3. Structure: Arrange-Act-Assert

**Rationale:** the Arrange-Act-Assert shape is the project's baseline test structure (per the `test-driven-development` skill's own convention); this section fixes the concrete Java shape so every test author writes the same shape rather than reinventing it per file.

**Model:**

```java
@Test
@DisplayName("marks a registration EXPIRED when the 15-minute hold has lapsed")
void marksExpired_whenHoldLapsed() {
    // Arrange
    var registration = aReservedRegistration()
        .withHoldExpiresAt(fixedClock.instant().minusSeconds(1))
        .build();

    // Act
    var result = expirySweep.process(registration.getId());

    // Assert
    assertThat(result.status()).isEqualTo(RegistrationStatus.EXPIRED);
}
```

| Rule | Convention |
|---|---|
| Section separation | Blank line + `// Arrange` / `// Act` / `// Assert` comments — cheap, greppable, and makes a three-part test instantly recognizable in a diff |
| Assertion library | AssertJ (`assertThat(...)`) — this document's own tool decision, not named in `CLAUDE.md`'s tech stack, chosen for fluent chaining and readable failure messages on collections/exceptions (`assertThatThrownBy`, `assertThat(list).containsExactly(...)`), the same way `mocking-guidelines.md` §2 makes its own WireMock decision |
| One assertion **concept** per test | Multiple `assertThat` calls verifying facets of the *same* outcome are fine; asserting two *unrelated* behaviors in one method is not — split it |
| Test independence | Each test builds its own fixtures (`test-data-management.md`) and asserts on state, never on which internal method was called (state-based, not interaction-based, per the `test-driven-development` skill) |

**Guarantees:** any test in this suite can be read top-to-bottom by someone who has never seen the test file before and understood, without cross-referencing another file, what condition is set up and what outcome is checked — DAMP over DRY inside the test body itself, even though fixture construction (`test-data-management.md`) is allowed to be shared.

---

## 4. Parameterized Tests for Catalogued Scenarios

**Rationale:** `test-design-techniques.md` §3 (Decision Tables) and §4 (Boundary Values) are already structured as tables with one row per scenario — writing each row as a hand-copied, near-identical `@Test` method would duplicate the table's structure in prose form and drift out of sync with it over time. JUnit 5's `@ParameterizedTest` maps directly onto a catalog table: one source row, one test invocation.

**Model:**

```java
@ParameterizedTest(name = "{0}")
@MethodSource("antiOverbookingScenarios") // one row per test-design-techniques.md §3.1
void antiOverbookingDecisionTable(String scenario, boolean lockAcquired,
                                    boolean versionMatches, boolean checkConstraintSatisfied,
                                    Outcome expected) {
    // Arrange — build the scenario's preconditions via the flags above
    // Act — attempt the reservation
    // Assert
    assertThat(outcome).isEqualTo(expected);
}
```

| Catalog source | JUnit 5 mechanism | Rule |
|---|---|---|
| `test-design-techniques.md` §3 (Decision Tables) | `@ParameterizedTest` + `@MethodSource` | One source-provider row per table row; the provider method's row order matches the catalog table's row order, so a diff in one is easy to cross-check against the other |
| `test-design-techniques.md` §4 (Boundary Values) | `@ParameterizedTest` + `@ValueSource`/`@CsvSource` | The exact fixed values from the catalog (e.g. `64, 65` for `ticket_code`), never re-derived or rounded |
| `test-design-techniques.md` §2 (State Transitions) | Individual `@Test` methods, not parameterized | Each transition (valid or must-reject) is a distinct, differently-named behavior (§2 above) — collapsing them into one parameterized method would lose the descriptive naming this file requires |

**Guarantees:** a catalog table in `test-design-techniques.md` and its corresponding `@MethodSource`/`@CsvSource` provider have the same row count — a reviewer can count both and know immediately if a scenario was dropped.

---

## 5. Reference Table

| Item | Value | Section |
|---|---|---|
| Unit suffix / task | `*Test.java` / `./gradlew test` | §1 |
| Integration suffix / task | `*IT.java` / `./gradlew integrationTest` | §1 |
| Method naming shape | `<observedBehavior>_<condition>`, always with `@DisplayName` | §2 |
| Structure | Arrange-Act-Assert, blank-line + comment separated | §3 |
| Assertion library | AssertJ | §3 |
| Decision-table/boundary tests | `@ParameterizedTest` (`@MethodSource`/`@CsvSource`), row-for-row against `test-design-techniques.md` | §4 |
| State-transition tests | Individual `@Test` methods, one per transition | §4 |
