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

    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer("rabbitmq:3.13.7-management");

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

**Phase 3 additions (checklist D21, D22, 2026-10-26):**

| Rule | Why |
|---|---|
| Every `*IT` context is given `unihub.redis.address` for the singleton `REDIS` above (through `IntegrationTestSupport`), not just the cache tests | Redis becomes required configuration: without an address the application refuses to start (`../../technical-design/platform/distributed-coordination.md` §1). Until Phase 3 most contexts ran without Redis |
| The workshop-catalog cache is isolated between tests (Redis flushed before each test, or the cache switched off for classes that do not test it) | With Redis wired everywhere the cache is live in every test, and a test that writes PostgreSQL directly and then reads through the API would otherwise read a stale entry |
| A class that injects a Redis fault (stop, pause) uses its **own** Redis container, never the singleton (`../non-functional/resilience-testing.md` §3) | Stopping the singleton would fail every later class in the run |
| A private container that is stopped and started again binds a **fixed** host port (a free port taken beforehand) | Testcontainers publishes a random host port, and Docker hands out a new one on restart, so the application's configured address would stop pointing at it (`RedisOutageIT`) |
| A test class that needs a different `spring.datasource.*` than the shared one extends the base that does **not** register it (`NotificationITBase`), not the one that does (`NotificationITSupport`) | Two `@DynamicPropertySource` methods setting the same key have no defined winner: a subclass override of a key its parent also sets was observed to lose (the context ran on the shared database), so an override must never share a key with an inherited registration |
| A context built with `SpringApplicationBuilder` takes its settings as **command-line arguments** (`run("--key=value")`), not `.properties(...)` | `.properties(...)` are default properties and lose to the system properties `IntegrationTestSupport` sets (listeners off, the shared SMTP, the relay off): a builder property for one of those keys is silently ignored (the Notification redelivery contexts started with no consumer) |
| A builder-made context registers `org.springframework.boot.test.context.filter.TestTypeExcludeFilter` itself | A `@SpringBootTest` context carries that filter, which keeps the nested `@TestConfiguration` classes of other test classes out of the component scan; a `SpringApplicationBuilder` context does not, so without it those classes (test channels, probes) are scanned in |
| The recording decorator of the multi-instance harness is registered on each context programmatically, never as a bean | Classes under `com.unihub` in the test source sets are component-scanned into every `@SpringBootTest` context; a decorator declared as a bean would wrap the lock of all of them |
| A class that runs the lifecycle of a seat hold or a refund on the real broker uses a **private** RabbitMQ whose hold window and retry delays are seconds (`unihub.registration.seat-hold`, `unihub.messaging.retry-delays`), shared as `ShortChainITSupport`; the relay stays off and the test publishes the outbox row it reads | Queue arguments cannot change once declared, so the shortened topology cannot live on the shared broker; the application declares it from the production properties, so the single source of the hold length is itself under test (checklist C2). Other cached contexts neither relay nor consume (`IntegrationTestSupport`), so nothing steals the messages |
| A test that needs a schema the production migrations do not produce (M-39's test-only notification channel, Phase 4 checklist D40) runs on a **database of its own**, created in the singleton Postgres container, with Flyway running the production migrations plus a test-only location (`db/testmigration`, versions far above any real one) | The shared schema must stay exactly what production gets; widening a CHECK there would let every other test pass against a schema that does not exist in production |
| `InProcessSeatLock`/`InProcessJobLock` exist only in test code | They are the control for the multi-instance tests below and the base for unit tests; production code has no in-process fallback |

**Multi-instance harness (M-32, M-35)** — built as `MultiInstanceHarness` (integration test source set; used by `JobSingleInstanceIT` for the job lock and by `SeatLockMultiInstanceIT` for the seat lock, whose holds are keyed `seat:{workshopId}` and can be padded separately, always far below the 5 s lease because there is no watchdog): two application contexts in the same JVM (`SpringApplicationBuilder`, random ports), both pointed at the singleton containers, **each with its own `RedissonClient`** — Redisson ties lock ownership to the client id plus the thread, so a shared client would make the two "instances" one. Neither context may share a bean or static state that holds a lock. The tests assert the lock itself, not only the business outcome, because the database check already makes the outcome correct without any lock: a test-only decorator around the `SeatLock`/`JobLock` port in each context records each hold as an acquire→release interval in a JVM-wide structure, and the test asserts that no two intervals on the same key overlap. The contention itself is driven over real HTTP with `java.net.http.HttpClient` (`RegistrationContention`), and the run reports how many requests were in flight at once: a test that asserts "under contention" must show that requests overlapped, since a client that queues them would pass with no contention at all. **Control:** the same harness with the in-process adapters must show overlapping intervals; if it does not, the harness is not actually separating the instances and the test proves nothing.

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
| INV-10, relay half (Phase 4 checklist D42) | Postgres + RabbitMQ (singleton; a private one for the pause tests) | A real business action per routing key → the real relay → the real consumer → its effect, with `PENDING` = 0 at the end; a broker `pause`/`unpause` (Docker, never a restart, so no fixed host port is needed) on a private container, with the relay's cycle timed to show that a frozen broker costs one bounded wait per cycle and not one per row; a test-only seam between the confirm and the `UPDATE` for the double publish | Explicit setup/teardown; bounded waits that report the rows still `PENDING` when they time out |
| INV-11 (every consumer idempotent under redelivery, Phase 4 checklist D41) | Postgres + RabbitMQ, plus the consumer's own stub (WireMock for Stripe, Mailpit for email) | The same message (same `message-id`) delivered ≥ 3 times sequentially through the real queue, and ≥ 2 times concurrently — use case called from ≥ 2 threads after a latch, or two contexts of `MultiInstanceHarness` for the notification consumer — with each processing interval recorded and the test failing unless they overlap; effects counted in SQL or on the stub, never messages | Explicit setup/teardown — the property spans transactions and threads |
| INV-12, server half (`checkin.md` §7 reconciliation; Phase 5 checklist D52, D61) | Postgres | (1) Every arrival order (or ≥ 24 seeded permutations) of ≥ 3 scans from ≥ 2 devices, including a tie on `scanned_at` → the same winner by `(scanned_at, id)`; (2) two batches for one registration sent concurrently after a latch, with processing intervals **measured** to overlap (no overlap → the test fails itself), plus a two-context `MultiInstanceHarness` variant → exactly one `ACCEPTED`, `attendances` agreeing with `outcome`; (3) a mutation per guard (no advisory lock, plain `<` tie-break, no workshop check, no `ON CONFLICT (id)`) turns its test red | **Corrected 2026-10-30:** reconciliation *is* a cross-connection race — REQ-2.9 requires concurrent batches from many devices — so, like INV-06, the concurrent tests commit for real and clean up explicitly; `@Transactional` rollback is fine only for the single-thread ordering tests |
| INV-19 (an Object Storage outage is isolated to the brochure flow, Phase 5 checklist 9.6, `resilience-testing.md` §2.6) | Postgres + RabbitMQ (a private one with a short retry chain) + a MinIO container of its own on a fixed host port + a WireMock gateway and AI stand-in | The bucket taken down by `stop` (refused) and by `pause` (accepts, never answers); `confirmBrochureUpload` must answer `503` problem `storage-unavailable` **within a bound** (20 s; a hang cannot pass as success) and persist nothing; a probe of catalog, detail, free and paid registration, check-in sync and publication stays `2xx` within 5 s for the whole window and after it; the same confirm succeeds once the bucket is back | Explicit setup/teardown; the MinIO `/data` on a named Docker volume removed after the class (`stop`/`start` on the overlay layer fails with `Rename across devices`); the restart retries on a new port if the fixed one was taken in the meantime |
| INV-14 (an audit-pipeline outage never blocks the business operation, and the publish failure surfaces as an alert; Phase 6 checklist D68, D72 — planned, not yet written) | Postgres + a private RabbitMQ (stopped, then paused) + for the alert half a Prometheus and a Grafana container provisioned from `observability/` | `AuditOutageIT`: registration, charge (gateway mocked), check-in sync and reschedule each `2xx` within a response-time bound during the fault; `unihub_audit_publish_failures_total` rises in a real scrape; rows resume after recovery. `AuditAlertIT`: the broker stopped, Grafana's alert API polled until the audit-failure rule is `Firing` | A broker, Prometheus and Grafana of the test's own; never the shared singleton broker |
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
| INV coverage pattern | See `test-strategy.md` §2 for the full list; §4 here shows 7 representative rows (INV-03, INV-06, INV-10 write and relay halves, INV-11, INV-12 server half, INV-19) | §4 |
