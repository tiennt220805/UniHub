# Test Coding Standards — UniHub Testing Guidelines

This file is the physical-level completion of `../strategy/test-strategy.md` §3 (Test Pyramid & Sizing) for the question that section doesn't answer: once a test's level and tooling are decided (JUnit 5 + Mockito for Unit, JUnit 5 + Testcontainers for Integration), what does the test code itself actually look like — file layout, naming, structure, assertions? Nothing here overrides `test-strategy.md` — where this file and that one appear to disagree, `test-strategy.md` is the authority and this file has a bug.

**Out of scope, and where it actually lives:** which technique derives a given test's cases (`test-design-techniques.md`); how fixture/seed data is built (`test-data-management.md`); when to mock vs. use real infrastructure (`mocking-guidelines.md`); which test level a piece of logic belongs in (`../test-levels/*.md`).

---

## Table of Contents

- [1. File & Package Naming](#1-file-package-naming)
- [2. Method Naming & `@DisplayName`](#2-method-naming-displayname)
- [3. Structure: Arrange-Act-Assert](#3-structure-arrange-act-assert)
- [4. Concurrency Test Construction](#4-concurrency-test-construction)
- [5. Parameterized Tests for Catalogued Scenarios](#5-parameterized-tests-for-catalogued-scenarios)
- [6. Reference Table](#6-reference-table)

---

## 1. File & Package Naming

**Rationale:** Java/Gradle convention puts test code under `src/test/java`, mirroring `src/main/java`'s package structure — there is no separate top-level `tests/` directory in this stack (unlike a Node/Python project), so the only remaining question is how to tell a Unit test apart from an Integration test in the same package without a second directory tree.

**Model:**

| Rule | Convention | Why |
|---|---|---|
| Package | `src/test/java/com/unihub/<module>/...` mirrors `src/main/java/com/unihub/<module>/...` exactly | The class under test and its test live at the same package depth — no separate navigation scheme to learn |
| Unit test suffix | `*Test.java` (e.g. `RegistrationServiceTest.java`) | Picked up by Gradle's default `test` task |
| Integration test suffix | `*IT.java` (e.g. `RegistrationConcurrencyIT.java`) | A distinct Gradle source set/task (`integrationTest`) filters on this suffix, so `./gradlew test` (fast, no Testcontainers) and `./gradlew integrationTest` (slower, spins up Postgres/Redis/RabbitMQ/MinIO) stay independently runnable — the actual `unit-test`/`integration-test` CI jobs already wired to exactly these two Gradle tasks in `../../technical-design/platform/deployment-and-cicd.md` §5, not a convention invented fresh here — matching `../strategy/test-strategy.md` §4's Testcontainers-per-run-per-tier model |
| One test class per production class | `WorkshopService` → `WorkshopServiceTest`; concurrency/cross-cutting scenarios (e.g. the anti-overbooking three-variant table in `test-design-techniques.md` §3.1) get their own named IT class instead of being folded into a single service's test class | A concurrency scenario spans more than one service's write path — forcing it into one class's test file misrepresents what it's actually proving |

**Guarantees:** `./gradlew test` never touches Testcontainers and never talks to a real Postgres/Redis/RabbitMQ/MinIO — a `*Test` class that needs real infrastructure is misnamed and belongs in `*IT` instead.

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

## 4. Concurrency Test Construction

**Rationale:** most of this suite's highest-value tests — anti-overbooking (`test-design-techniques.md` §3.1), idempotency (§8 of `payment.md`), redelivery (INV-11) — are concurrency tests, not sequential ones, yet nothing so far in this document says how to actually construct "N threads race the same row." Getting this wrong silently turns a concurrency test into a sequential one that happens to compile.

**Model:**

```java
@Test
@DisplayName("accepts exactly the remaining seats when concurrent requests exceed capacity")
void acceptsExactlyRemainingSeats_whenConcurrentRequestsExceedCapacity() throws InterruptedException {
    // Arrange
    int concurrentRequests = 10;
    var workshop = aPublishedWorkshop().withCapacity(10).withSeatsTaken(7).build(); // 3 seats left
    var pool = Executors.newFixedThreadPool(concurrentRequests);
    var startingGate = new CountDownLatch(1);          // holds every thread at the same starting line
    var readyCount = new CountDownLatch(concurrentRequests); // confirms every thread is actually waiting
    List<Future<RegistrationResult>> futures = new ArrayList<>();

    for (int i = 0; i < concurrentRequests; i++) {
        futures.add(pool.submit(() -> {
            readyCount.countDown();
            startingGate.await();               // every thread blocks here until released together
            return registrationService.reserve(workshop.getId(), anActiveUser().build().getId());
        }));
    }

    // Act
    readyCount.await();       // don't release until all threads are actually queued up
    startingGate.countDown(); // release all threads at once — this is the race
    pool.shutdown();
    boolean finished = pool.awaitTermination(10, TimeUnit.SECONDS); // mandatory — see Guarantees

    // Assert — aggregate over all results, never assert on a specific thread/future by index
    var results = futures.stream().map(this::getResultOrFailure).toList();
    assertThat(finished).isTrue();
    assertThat(results).filteredOn(RegistrationResult::isAccepted).hasSize(3);
    assertThat(results).filteredOn(r -> !r.isAccepted()).hasSize(7)
        .allSatisfy(r -> assertThat(r.statusCode()).isEqualTo(409));
}
```

| Rule | Convention |
|---|---|
| Thread launch | `Executors.newFixedThreadPool(n)` sized to the exact number of concurrent requests the scenario needs — never a shared/cached pool another test could also be drawing threads from |
| Synchronized start | A `CountDownLatch` gate that every worker `await()`s on before doing anything else, released once with a single `countDown()` from the test thread — this is what makes it a genuine race instead of N sequential calls that merely happen not to block each other. A second latch confirming every worker has actually reached the gate (`readyCount` above) avoids releasing the race before all threads are queued up. |
| Result collection | `List<Future<Result>>`, one per submitted task, collected via `future.get()` after the pool is shut down — never a shared mutable field written by multiple threads without its own synchronization (a `ConcurrentLinkedQueue` is the alternative if a task's return value alone can't carry enough information) |
| Assertion shape | Aggregate over the whole result collection — count of accepted vs. rejected, and a shared property of the rejected set (e.g. "all rejected are `409`") — **never** assert on `futures.get(3)` or otherwise assume which thread "won"; race outcomes are not deterministic by thread index |
| Termination timeout | `awaitTermination(...)` with an explicit bound is mandatory, and its return value is asserted — an unbounded wait turns a deadlock in the code under test into a hung CI job instead of a failing test |
| Naming | Same `<observedBehavior>_<condition>` shape as §2, but the condition names the concurrency scenario explicitly (the number of competing requests, what's being contended) — e.g. `acceptsExactlyRemainingSeats_whenConcurrentRequestsExceedCapacity`, not `testConcurrency()` |
| File / isolation | `*IT.java` (§1) — these are Testcontainers-backed by construction, never `*Test.java`. Isolation is explicit setup/teardown, never `@Transactional` rollback — `test-data-management.md` §4 owns that rule and the reasoning behind it; this section doesn't repeat it |

**Guarantees:** every concurrency test in this suite has a bounded `awaitTermination` and asserts on the aggregate result set, not on thread-execution order — a test that assumes which thread wins a race is asserting an implementation detail the JVM's scheduler doesn't promise, and will flake under exactly the load conditions (`proposal.md` §12's 500-VU scenario) it exists to prove correctness under.

---

## 5. Parameterized Tests for Catalogued Scenarios

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

## 6. Reference Table

| Item | Value | Section |
|---|---|---|
| Unit suffix / task | `*Test.java` / `./gradlew test` | §1 |
| Integration suffix / task | `*IT.java` / `./gradlew integrationTest` | §1 |
| Method naming shape | `<observedBehavior>_<condition>`, always with `@DisplayName` | §2 |
| Structure | Arrange-Act-Assert, blank-line + comment separated | §3 |
| Assertion library | AssertJ | §3 |
| Concurrency test shape | Fixed-size `ExecutorService` + `CountDownLatch` start gate + bounded `awaitTermination`, aggregate assertions only | §4 |
| Decision-table/boundary tests | `@ParameterizedTest` (`@MethodSource`/`@CsvSource`), row-for-row against `test-design-techniques.md` | §5 |
| State-transition tests | Individual `@Test` methods, one per transition | §5 |
