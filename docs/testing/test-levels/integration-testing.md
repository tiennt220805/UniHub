# Integration Testing — UniHub Test Levels

This file is the physical-level completion of `../strategy/test-strategy.md` §3's Integration row (JUnit 5 + Testcontainers, ~25% target share, "Medium" resource size) — the tier carrying the bulk of `test-strategy.md` §2's Non-Negotiable Invariant Catalog. Nothing here overrides `test-strategy.md` — where this file and that one appear to disagree, `test-strategy.md` is the authority and this file has a bug.

**Out of scope, and where it actually lives:** when to mock vs. use real infrastructure, and which external HTTP boundaries get stubbed via WireMock (`../guidelines/mocking-guidelines.md`); concurrency-test construction (`ExecutorService`/`CountDownLatch` shape) (`../guidelines/coding-standards.md` §4); fixture builders (`../guidelines/test-data-management.md` §§1–2) and the explicit-setup-vs-`@Transactional`-rollback isolation rule (`../guidelines/test-data-management.md` §4); the full, authoritative list of which INV rows are Testcontainers-backed (`../strategy/test-strategy.md` §2 — this file cites illustrative rows, not the complete list).

---

## Table of Contents

- [1. Testcontainers Stack](#1-testcontainers-stack)
- [2. Container Lifecycle: Singleton Pattern](#2-container-lifecycle-singleton-pattern)
- [3. Gradle Wiring & CI Gate](#3-gradle-wiring-ci-gate)
- [4. Coverage of the Invariant Catalog](#4-coverage-of-the-invariant-catalog)
- [5. Reference Table](#5-reference-table)

---

## 1. Testcontainers Stack

**Rationale:** the real CI pipeline (`deployment-and-cicd.md` §5) already fixes the exact containers this level runs against — this section cites that job rather than re-deciding it.

**Model:** four containers, plus one conditional fifth (the replica), all started by Testcontainers itself against the Docker daemon already present on the runner — no `services:` stanza, unlike a fixed-sidecar setup:

| Container | Stands in for | Why real (not mocked) |
|---|---|---|
| PostgreSQL | `../../technical-design/platform/database-schema.md` | Transaction isolation, locking — `requirement.md` §3.2 forbids H2 explicitly |
| PostgreSQL streaming replica (a standby of the Master above) | `architecture.md` §4.1's read/write split | INV-03: a routing test against a caught-up replica cannot reveal a misrouted read, so the fixture must let a test pause WAL replay and make staleness real (`../non-functional/resilience-testing.md` §2.5). Started only by classes that extend `ReplicaIntegrationTestSupport` (§2) |
| Redis | `../../technical-design/platform/distributed-coordination.md` | Distributed-lock semantics under real contention (INV-06, INV-08, INV-09) |
| RabbitMQ | `../../technical-design/platform/messaging.md` | At-least-once redelivery, outbox-relay timing (INV-10, INV-11) |
| MinIO | AWS S3 (`../../technical-design/modules/workshop.md`) | S3-compatible API, real strong-read-after-write semantics — `../guidelines/mocking-guidelines.md` §1 |

**Guarantees:** every container in this table is real infrastructure, never an in-memory substitute — this is the same guarantee `unit-testing.md` §4 states in the negative (nothing here belongs at Unit level, and nothing at Unit level may fake its way into this table's territory).

---

## 2. Container Lifecycle: Singleton Pattern

**Rationale:** `../guidelines/mocking-guidelines.md`'s own "Out of scope, and where it actually lives" note explicitly defers "Testcontainers container-lifecycle mechanics (reuse, startup ordering)" to this file — restarting all four containers per test class (or worse, per test method) would make the Integration suite too slow to run on every push, undermining `test-strategy.md` §4's every-push CI gate.

**Model:** one shared instance of each container per `integrationTest` JVM run, not per test class. **That requires a static initializer — not `@Container` fields.** An earlier version of this section showed `@Container static final` fields on the base class and described them as JVM-wide; that was an assumption, and a small experiment showed it is wrong. Two abstract base classes, each with three `*IT` subclasses, run through an `integrationTest`-style Gradle task (Testcontainers 1.21.4, JUnit 5.11.4, Gradle 9.2.0, Docker 29.1.3, one JVM), logging the Postgres and Redis container IDs each class saw:

| Base class shape | IDs seen across the three subclasses | Observed lifecycle |
|---|---|---|
| `@Testcontainers` base with `@Container static final` fields (what this section previously showed) | Three different Postgres IDs and three different Redis IDs, all in the same JVM | Started before and stopped after **each test class** — per-class, not per-run |
| Plain base class, containers started in a `static { ... }` block | One Postgres ID and one Redis ID | Started once, when the base class is first loaded; the containers were still running when the Gradle process exited, and Ryuk removed them shortly afterwards |

So `@Container` on a static field of a shared base class gives exactly the per-class restart this section exists to avoid. The experiment covered Postgres and Redis (same mechanism as the other two containers); it did **not** exercise `withReuse(true)` with reuse enabled, or parallel test execution.

```java
public abstract class IntegrationTestSupport {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withReuse(true); // local dev only — see below

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7").withExposedPorts(6379);

    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer("rabbitmq:3-management");

    // minio/minio stopped being pullable (Docker Hub repository removed, 10/2025 — verified against
    // live Docker Hub/quay.io, not a sandbox/network issue). cgr.dev/chainguard/minio is a real MinIO
    // build under a different registry/namespace; asCompatibleSubstituteFor is Testcontainers' own
    // mechanism for exactly this case — verified end-to-end: the container still starts and its
    // /minio/health/live check still passes, not just "no constructor exception."
    static final MinIOContainer MINIO = new MinIOContainer(
        DockerImageName.parse("cgr.dev/chainguard/minio:latest").asCompatibleSubstituteFor("minio/minio"));

    static {
        // Started once, when this class is first loaded by the JVM. No @Container/@Testcontainers:
        // those give per-class start/stop (table above). Testcontainers' Ryuk reaps these after JVM exit.
        POSTGRES.start();
        REDIS.start();
        RABBITMQ.start();
        MINIO.start();
    }
}
```

`postgres:16-alpine` is the same image the Kustomize base runs (`deployment-and-cicd.md` §2, §6) — an earlier version here said `postgres:16`, which contradicted that document's own guarantee that Testcontainers and the manifests pull the same image. No technical reason was found to prefer the Debian image: `pg_basebackup` (needed for the replica below) and `pg_isready` are both present in `postgres:16-alpine`.

Every `*IT.java` class extends `IntegrationTestSupport` rather than declaring its own `@Container` fields — one base class is where "what does a legal Postgres/Redis/RabbitMQ/MinIO instance for this suite look like" is answered once, the same reasoning `../guidelines/test-data-management.md` §1 applies to entity builders.

**The replica is the one conditional container.** It has its own base class so only INV-03 test classes pay its startup cost:

```java
public abstract class ReplicaIntegrationTestSupport extends IntegrationTestSupport {

    // A physical standby cloned from POSTGRES's base backup over a shared Testcontainers
    // Network; needs a replication role and a pg_hba entry on the Master. Helper name and
    // wiring are illustrative — the fixture detail belongs in the test-support code.
    static final PostgreSQLContainer<?> POSTGRES_REPLICA = createStandbyOf(POSTGRES);

    static { POSTGRES_REPLICA.start(); } // JVM-wide, and only if a subclass is ever loaded
}
```

| Rule | Why |
|---|---|
| Every other `*IT` points **both** its write and its read datasource at the Master, as before | A real replica behind ordinary tests would make routine read-after-write assertions flaky through ordinary replication delay — exactly the staleness `architecture.md` §4.1 accepts in production and a general-purpose test cannot |
| A replica test resumes WAL replay in a `finally`/`@AfterEach` and asserts it (`pg_is_wal_replay_paused()` is false) | The standby is a JVM-wide singleton: a paused replica left behind would make every later replica-using class read stale |
| `.withReuse(true)` is **not** set on the replica | A standby is only valid against the exact Master it was cloned from |
| Explicit setup/teardown, never `@Transactional` rollback | The property under test spans connections and transactions (`../guidelines/test-data-management.md` §4) |

| Environment | Reuse behavior |
|---|---|
| CI (GitHub Actions) | One fresh set of containers per workflow run — `.withReuse(true)` has no effect across runs on an ephemeral runner, but costs nothing to leave set |
| Local dev | `.withReuse(true)` + a `~/.testcontainers.properties` opt-in lets repeated `./gradlew integrationTest` invocations reuse the same Postgres container across runs, cutting iteration time — genuinely optional developer QoL, never relied on for correctness |

**Guarantees:** a test's outcome never depends on whether a container was freshly started or reused — reuse is a startup-time optimization only; `../guidelines/test-data-management.md` §4's isolation rules (transaction rollback vs. explicit teardown) are what actually keep tests from interfering with each other, independent of container lifecycle.

---

## 3. Gradle Wiring & CI Gate

**Rationale:** cites the real job, does not re-derive it.

**Model:**

| Job | Gated by | Command |
|---|---|---|
| `integration-test` | `unit-test` success | `./gradlew integrationTest --no-daemon` |

`integration-test` in turn gates `contract-diff` (`deployment-and-cicd.md` §5) — ordered after the cheaper `unit-test` job and before the cheaper-still `contract-diff` job, per that document's CI Job Structuring ADR ("ordering it last among the test-gate jobs means a contract mismatch never wastes the time of a run that was already going to fail on lint/unit/integration anyway" — read in reverse, the same reasoning is why Integration runs after Unit, not before).

**Guarantees:** `./gradlew integrationTest` is the only Gradle task in this pipeline that touches Testcontainers — `unit-testing.md` §2's `./gradlew test` never does, and `contract-diff`'s `./gradlew generateOpenApiDocs` (`contract-testing.md` §2) boots only a minimal annotation-reading Spring context, not this one.

---

## 4. Coverage of the Invariant Catalog

**Rationale:** `test-strategy.md` §2 already lists which INV rows are `Testcontainers: Y` — restating all of them here would drift out of sync with that table over time. This section instead shows, through four representative rows, how the mechanisms in `../guidelines/coding-standards.md` and `../guidelines/test-data-management.md` compose to actually prove an invariant at this level — the pattern, not the full list.

**Model:**

| INV | What's real | Test construction | Isolation |
|---|---|---|---|
| INV-06 (anti-overbooking, `test-design-techniques.md` §3.1's three-variant decision table) | Postgres + Redis | `../guidelines/coding-standards.md` §4's `ExecutorService`/`CountDownLatch` race, one `*IT` class per variant (full-stack / lock-step-skipped / DB-only) | `../guidelines/test-data-management.md` §4 — explicit setup/teardown, never `@Transactional` rollback (a rolled-back transaction would hide one thread's writes from the others) |
| INV-10 (outbox atomicity) | Postgres (write side, Phase 2) + RabbitMQ (relay side, Phase 4) | `../guidelines/test-data-management.md` §3's `event_publications` fixture, inserted in the *same* setup transaction as the business row it announces | Explicit setup/teardown — the property under test is specifically what happens across the transaction boundary, so rollback-per-test would hide it (`test-data-management.md` §4) |
| INV-12, server half (`checkin.md` §7 reconciliation) | Postgres | Multiple simulated devices' scan rows inserted with distinct `scanned_at` values, reconciliation run, asserted earliest-wins | Ordinary `@Transactional` rollback is fine here — reconciliation is a single-thread, single-transaction read-then-decide, not a cross-connection race (contrast with INV-06 above) |
| INV-03 (Master-routed reads, `architecture.md` §4.1) | Postgres Master + streaming replica | Replica WAL replay paused, write on the Master, then assert the consistency-sensitive reads (post-write view, idempotency lookup) still see it (`../non-functional/resilience-testing.md` §2.5) | Explicit setup/teardown; replay resumed in a `finally` (§2's replica rules) |

**Guarantees:** every row in this table cross-references `test-strategy.md` §2's authoritative catalog by INV ID — a test author who wants the complete list of what belongs at this level reads that table, not this one; this table only demonstrates *how*.

---

## 5. Reference Table

| Item | Value | Section |
|---|---|---|
| Testcontainers stack | Postgres, Redis, RabbitMQ, MinIO — plus a Postgres streaming replica, INV-03 classes only | §1 |
| Container lifecycle | Singleton per JVM run via a `static { ... }` initializer in a shared base class (`@Container` fields would restart per class — measured, §2); `.withReuse(true)` opt-in for local dev only; the replica is a lazily-started singleton in `ReplicaIntegrationTestSupport`, never reused | §2 |
| Gradle task | `./gradlew integrationTest` | §3 |
| CI job | `integration-test`, gated by `unit-test`, gates `contract-diff` | §3 |
| INV coverage pattern | See `test-strategy.md` §2 for the full list; §4 here shows 4 representative rows | §4 |
