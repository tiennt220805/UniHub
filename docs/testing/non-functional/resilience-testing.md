# Resilience Testing — UniHub Non-Functional Testing

This file is the physical-level completion of `../strategy/test-strategy.md` §1's "Prove fault isolation" objective (`proposal.md` §12) and of the fault-related rows of §2's Non-Negotiable Invariant Catalog (INV-03, INV-05, INV-08, INV-11, INV-14, INV-16, INV-19), for the question those files raise but don't answer: *what fault is injected, by what mechanism, and what must be shown to stay unaffected while it is active?* That is a different question from *what decision does the system take in a given fault state* — `../guidelines/test-design-techniques.md` §3 already tabulates those decisions, and this file never re-derives them. Nothing here overrides `test-strategy.md`/`test-plan.md` — where this file and they appear to disagree, those two are the authority and this file has a bug.

**Out of scope, and where it actually lives:** the circuit-breaker state × call-site decision table (`../guidelines/test-design-techniques.md` §3.3) and the Resilience4j parameters behind it (`../../technical-design/modules/payment.md` §2–§5); the anti-overbooking lock/version/constraint decision table, including the "lock step skipped, Redis reachable" defense-in-depth variants (`../guidelines/test-design-techniques.md` §3.1); the real-vs-mock rule and WireMock stub mechanism (`../guidelines/mocking-guidelines.md` §1–§2); the Testcontainers stack and its singleton lifecycle (`../test-levels/integration-testing.md` §1–§2); a single consumer's idempotency under redelivery (`../strategy/test-plan.md` Phase 4, INV-11; `../../technical-design/platform/messaging.md` §4); load-scale behavior (`performance-load.md`); adversarial security vectors (`security-testing.md`).

**Gating:** this file adds no CI gate of its own. A scenario below is blocking on every push **only** where `../strategy/test-plan.md` already schedules the matching INV row's Integration-level test at that phase (`test-strategy.md` §2's Level column, §4 stage 3) — this file then supplies the fault-injection mechanics that test needs. Every other scenario is on-demand or a Phase 7 manual step. The "Runs" column of §2.1 states which is which for every scenario.

**Citation convention:** design files cited by bare name (`payment.md`, `registration.md`, `messaging.md`, `distributed-coordination.md`, `observability.md`, `auth-and-identity.md`, `checkin.md`, `workshop.md`, `notification.md`, `deployment-and-cicd.md`, `database-schema.md`) live under `../../technical-design/modules/` or `../../technical-design/platform/`; `architecture.md`, `proposal.md`, `requirement.md` and `CLAUDE.md` are at the repository's `docs/` root and root respectively.

---

## Table of Contents

- [1. Objectives](#1-objectives)
- [2. Fault-Injection Catalog](#2-fault-injection-catalog)
- [3. Tooling](#3-tooling)
- [4. Guarantees](#4-guarantees)
- [5. Reference Table](#5-reference-table)

---

## 1. Objectives

**Rationale:** `proposal.md` §12's Fault Isolation row names only the Payment Gateway, but `architecture.md` §1.4, §4.1–§4.4 and §5.5 apply the same instinct to five more dependencies — and each states a *different, deliberate failure direction*: Redis-backed registration writes fail **closed**, the rate limiter fails **open**, audit fails **open with an alert**, S3 is **isolated**. A resilience suite that only asserts "the system survived" cannot tell a correct fail-closed from a bug; each scenario here asserts the direction the corpus specifies.

**Model:**

| Dependency | Specified failure direction | Must stay unaffected | Source | INV |
|---|---|---|---|---|
| Payment Gateway | Degrade the paid-checkout path only (`503`, hold untouched) | Browsing, free registration, check-in, reporting, the webhook endpoint, K8s Readiness | `architecture.md` §1.4; `payment.md` §10; `observability.md` §5 | INV-05 |
| Redis | Registration writes **rejected** (`503`); job lock **skips** the cycle; rate limiter **fails open**; cache **falls through** | Catalog reads, check-in sync, reporting, payment webhook; every pod stays `Ready` | `architecture.md` §4.2; `distributed-coordination.md` §4; `observability.md` §5 (Readiness Composition ADR) | INV-08 (+ INV-09's skip side) |
| RabbitMQ | Publish is decoupled via the outbox; consumers redeliver at-least-once | The HTTP request path that produced the event | `architecture.md` §4.3, §6.1; `messaging.md` §1, §4 | INV-10, INV-11 |
| PostgreSQL Replica | Stale reads tolerated for routine reads only; consistency-sensitive reads go to the Master | Idempotency correctness, the seat-reservation decision | `architecture.md` §4.1 | INV-03 |
| AWS S3 | Isolated to the brochure upload/summarization flow | Registration, Payment, Check-in (and browsing) | `architecture.md` §4.4; `workshop.md` §10 | INV-19 |
| Audit pipeline | Fail-open, and the publish failure itself must surface as an operational alert | The registration/payment/check-in being audited | `architecture.md` §5.5; `messaging.md` §3 | INV-14 |
| Backend API pod | Restarted automatically; traffic only to Ready pods | Availability through the remaining pods | `requirement.md` §3.3; `observability.md` §5 | INV-16 |

**Guarantees:** every row maps to one scenario in §2. A scenario that passes by asserting only that the process didn't crash — rather than the direction in the "Specified failure direction" column — is not a passing resilience test.

---

## 2. Fault-Injection Catalog

**Rationale:** `test-plan.md` Phase 2's exit criterion already says the breaker test must prove the outage "never cascades into browsing/check-in" — and a test that only reads the breaker's state can't prove that. Every scenario therefore runs the same *concurrent probe* (§2.1) while the fault is active, and asserts on the probe's results, not just on the faulted path.

### 2.1 The concurrent probe, and the scenario index

**The probe.** For the whole fault window — from just before injection to just after recovery — a separate bounded thread pool (`../guidelines/coding-standards.md` §4's shape: fixed pool, aggregate assertions, bounded `awaitTermination`) keeps issuing requests that do **not** depend on the faulted dependency: `GET /api/v1/workshops`, `GET /api/v1/workshops/{id}`, `POST /api/v1/checkin/sync` (a small batch, token carrying `CHECKIN_SCAN`), `GET /api/v1/workshops/{workshopId}/attendances` (token carrying `REPORT_VIEW`), and — where the scenario says so — a free-workshop `POST /api/v1/registrations`. Assertions: (a) every probe response is `2xx` except the documented exceptions the scenario names; (b) probe latency during the fault stays within a bound relative to a fault-free baseline measured in the same test immediately before injection — **TODO: the multiplier has no source in `proposal.md`/`requirement.md`/`technical-design/`; until the user fixes one, assert only that no probe request hits the client-side timeout.**

**Scenario index:**

| # | Fault | INV | Tool | Runs |
|---|---|---|---|---|
| 1 | Payment Gateway dead or slow | INV-05 | WireMock | Per-push `*IT` (Phase 2); 1b: Phase 7 manual, full topology |
| 2 | Redis unreachable (refused, then hung) | INV-08 | Testcontainers stop/pause | Per-push `*IT` (Phase 3); 2b: Phase 7 manual, full topology |
| 3 | RabbitMQ interruption and recovery | INV-10, INV-11 | Testcontainers stop/restart | On-demand |
| 4 | Replica lag | INV-03 | Postgres replica fixture | Per-push `*IT` (Phase 2 — INV-03's fixture, `integration-testing.md` §1) |
| 5 | S3 outage | INV-19 | MinIO stop/pause | Per-push `*IT` (Phase 5) |
| 6 | Audit pipeline outage | INV-14 | Testcontainers stop | Per-push `*IT` (Phase 6) |
| 7 | Backend API pod kill | INV-16 | `kubectl` | Phase 7 manual, full topology only |

Scenarios 1–6 run at Integration level on local Testcontainers — **only scenario 7 (and 1b, 2b) needs Minikube.**

**Scenario 3 is on-demand by design, and is not what proves INV-10/INV-11 for Phase 4.** It checks real *recovery* behavior when RabbitMQ is interrupted (container stop/restart) — additional evidence, stronger than the per-push proof, not a condition for Phase 4's INV-10/INV-11 rows to be `Passing`. Those rows are proven per-push at ordinary Integration level (`integration-testing.md` §4's INV-10 and INV-11 rows; `test-matrix.md` M-40, M-41). Per push, Phase 4 also covers a broker that is **paused** (reachable, not confirming) for the relay half of INV-10 (Phase 4 checklist D42); the container *stop/restart* of this scenario stays on-demand.

### 2.2 Scenario 1 — Payment Gateway dead or slow (INV-05)

- **Inject:** WireMock stub of the gateway (`mocking-guidelines.md` §2), two variants: server stopped (connection refused), and `withFixedDelay` above the sync path's 3 s `TimeLimiter`/client timeout (`payment.md` §3). Drive at least 20 failing `POST /api/v1/payments/charge` calls (the count-based window, `payment.md` §2). (`minimumNumberOfCalls` is 10, decided in Phase 2 checklist D12 and now in `payment.md` §2; tests lower it with `unihub.payment.breaker.minimum-number-of-calls`.)
- **Expected:** once OPEN, further charges return `503` with no gateway call attempted — proven by the WireMock request journal staying flat for the OPEN period (`verify` count unchanged), which is stronger than reading breaker state. What each state does to each call-site is `test-design-techniques.md` §3.3, not repeated.
- **Must stay unaffected (probe):** browsing, free-workshop registration (no gateway coupling — `test-design-techniques.md` §5's first pairwise row), check-in sync, reporting, and `POST /api/v1/webhooks/payment-gateway` with a valid signature (`200`; the webhook never touches the breaker, `payment.md` §7) — all `2xx`. **Phasing (amended 2026-10-26):** Phase 2 probes browsing, free registration and the webhook (`PaymentCircuitBreakerIT#freeRegistration_browsing_andTheWebhook_areUnaffected`); check-in sync and reporting have no module to probe until Phase 5, where the same probe is extended (M-80). **Done 2026-11-01 (Phase 5 checklist 5.3):** `PaymentCircuitBreakerIT#checkInSync_andReporting_areUnaffected` runs with the gateway really dead and the circuit open, then half-open and closed: `POST /api/v1/checkin/sync`, `GET …/report` and `GET …/attendances` answer `2xx` within 2 s, sync stores its scan, the gateway journal stays empty and the circuit does not move. The latency bound is absolute; the multiplier over a baseline that §2.1 leaves as a TODO has no source and is not invented.
- **Recovery:** stub restored, wait past the 30 s open duration; the breaker re-closes and a charge succeeds with no manual step.
- **1b (Phase 7, full topology, manual):** with the gateway stub dead and the breaker OPEN, every `backend-api` pod stays `Ready` and the Ingress keeps serving the probe. This is the Kubernetes-layer half of INV-05: `observability.md` §5 excludes the gateway from Readiness precisely so this holds. **TODO: where the WireMock stub runs in the cluster is not specified (`mocking-guidelines.md` §2 says it "carries forward" at Phase 8 without naming a placement). If it runs inside `unihub` it must be sidecar-injected once the mesh is STRICT (the target, not yet in force — `deployment-and-cicd.md` §2, Mesh mTLS Mode ADR).**

### 2.3 Scenario 2 — Redis unreachable (INV-08)

This is the scenario `test-design-techniques.md` §3.1's note hands off to this file.

- **Inject:** against a **dedicated** Redis container (§3), two variants — stopped (connection refused) and paused (connections accepted, never answered). The second matters because a hung connection reaches the application as a timeout, not as the connection-refused failure. **Settled 2026-10-26 (Phase 3 checklist D20): every `RedisException` counts as "no lock", including the `RedisTimeoutException` a hung connection produces after the shared client's 0.5 s command timeout (`registration.md` §2, `distributed-coordination.md` §2). The `pause` variant is what proves it on the pinned Redisson. The test asserts the outcome, never the exception type.**
- **Expected:** `POST /api/v1/registrations` → `503` with `Retry-After` (`registration.md` §2, §6; the contract's `503`), **and no write reached PostgreSQL**: `seats_taken` unchanged, zero new `registrations` rows, zero new `idempotency_keys` rows. Run it under concurrency (N threads, workshop with seats remaining): all rejected, zero accepted — seats being available is exactly why "fell through uncoordinated" is the failure this scenario exists to catch.
- **Also asserted (each a documented direction, `distributed-coordination.md` §4):** workshop list/detail still return `200` (cache falls through); non-registration endpoints are not `429`/`500` because Redis is down (limiter fails open); a scheduled-job tick skips without running the job body (INV-09's skip side, observed through a side-effect counter on the job). **Not assertable in Phase 3** (Phase 3 checklist C9): the limiter-fails-open check needs the rate limiter (INV-17, Phase 7), and the check-in sync and reporting probes below need those modules (Phase 5). Both join this scenario in those phases; neither is part of INV-08's exit criterion ("writes are rejected, never uncoordinated"). **Check-in and reporting joined 2026-11-01 (Phase 5 checklist 5.5):** `RedisOutageIT`, in both the stopped and the paused variant, also sends sync, the report and the attendance list and asserts `2xx` within the same bound as the refused registrations, with the sync's scan stored; the rate limiter's half stays Phase 7.
- **Must stay unaffected (probe):** browsing, check-in sync, reporting, payment webhook. Free registration is deliberately *not* in this probe — it fails closed by design and is asserted above.
- **Recovery:** container restarted/unpaused; registrations succeed again with no application restart, within an explicit bounded wait.
- **2b (Phase 7, full topology, manual):** with Redis stopped — then, separately, RabbitMQ — every `backend-api` pod stays `Ready`, the Ingress keeps serving the probe, and the `dependencies` health group reports `DEGRADED`. This is the Kubernetes-layer half of this scenario, and it holds because `observability.md` §5's Readiness Composition ADR keeps Redis and RabbitMQ out of Readiness. `db` (the PostgreSQL Master) *is* Readiness-critical: a Master outage should make pods `NotReady`.
- **Hold expiry during the outage (settled 2026-10-26, Phase 3 checklist D19):** the hold-expiry consumer takes the same seat lock (`registration.md` §3). A message that cannot take it is kept at its retry chain's 5-minute stage for as long as Redis is down, never parked (`messaging.md` §4, exception table). **Asserted:** with Redis down for longer than the whole 3-stage chain (delays shortened in the test), an expired hold is not in `unihub.parking-lot`, and after recovery it reaches `EXPIRED` with `seats_taken` lowered by exactly one, with no manual step.

### 2.4 Scenario 3 — RabbitMQ interruption and recovery (INV-10, INV-11)

**Rationale:** on-demand supplementary evidence of recovery under a real broker interruption; not required for Phase 4's INV-10/INV-11 `Passing` (see §2.1 — those are proven per-push at ordinary Integration level, `test-matrix.md` M-40, M-41).

- **Inject:** against a dedicated RabbitMQ container, (a) stop it while `event_publications` rows are `PENDING` and consumers are mid-processing, then start it; (b) restart it (same container, filesystem kept) with a `hold.reserved` message sitting in the delay queue, using a shortened TTL wherever that value is configuration-sourced (`mocking-guidelines.md` §3). The hold window is configuration: the delay queue's TTL and `hold_expires_at` both come from `unihub.registration.seat-hold`, and the retry stages from `unihub.messaging.retry-delays` (Phase 3 checklist C2, 3.1), so a test broker declares them in seconds; the queue arguments are immutable, so it must be a broker of its own (`integration-testing.md`, `ShortChainITSupport`).
- **Expected:**
  1. *Write path unaffected while the broker is down:* a free `POST /api/v1/registrations` still returns `201` and its outbox row commits `PENDING` — the publish is asynchronous by design (`architecture.md` §6.1).
  2. *Zero stranded events after recovery:* every `PENDING` row reaches `PUBLISHED`.
  3. *Durability:* the delay-queue message survives (b) and still dead-letters to `hold.expired` (`architecture.md` §4.3's durability guarantee).
  4. *Effects, not messages, are counted:* one seat release per expired hold, one reconciliation resolution per payment, one dispatched notification per fact. **The suite must not assert "no duplicate message" — at-least-once means the relay may legitimately republish a row whose confirm was lost; INV-11 is that a duplicate has no duplicate effect.** The Notification consumer is no longer an exception (Phase 4 checklist D31, D33): duplicate rows are closed by a unique key and duplicate sends by a per-row claim, so its effect count is asserted under concurrent redelivery too; only the documented provider-side at-least-once window (a crash after the provider accepted, before the row is marked, `notification.md` §3) may add one send.
- **Must stay unaffected (probe):** browsing, reporting, check-in sync.
- **Not repeated:** a consumer's re-check-before-acting mechanics (Phase 4's INV-11 test).

### 2.5 Scenario 4 — Replica lag (INV-03)

- **Rationale for a separate scenario:** a routing test against a fully caught-up replica passes even if a consistency-sensitive read were wrongly sent to the Replica, because replication delay is milliseconds and the stale read never becomes visible. Pausing WAL replay makes the stale read deterministic — so this is the *fault condition* INV-03's Phase 2 test needs, not a second test on top of it.
- **Inject:** the Master + streaming-replica pair of `../test-levels/integration-testing.md` §1–§2 (`ReplicaIntegrationTestSupport`): `SELECT pg_wal_replay_pause()` on the standby, write on the Master, `pg_wal_replay_resume()` in a `finally`. This fixture is INV-03's Phase 2 fixture, so this scenario runs per-push (`test-plan.md` Phase 2). `overlays/dev` still runs zero replicas (`deployment-and-cicd.md` §3); that is irrelevant here, since these are Testcontainers tests.
- **Expected, with replay paused:** (a) an Organizer's `GET /api/v1/workshops/{id}` immediately after creating it returns the new workshop (`architecture.md` §4.1's own example of a Master-routed read); (b) a retried `POST /api/v1/registrations` with a used `Idempotency-Key` replays the original response — exactly one `registrations` row, `seats_taken` incremented once; (c) concurrent registrations respect capacity even though the Replica shows a stale `seats_taken` (the decision is never made from a Replica read — the INV-06 assertion, referenced, not redefined); (d) routine list reads may serve the pre-write state without error, and converge after resume.
- **Not asserted — direction approved, mechanism missing:** when the Replica is *unreachable* (not lagging), reads are to fall back to the Master. That is recorded here and deliberately **not** as a final design decision anywhere else, because the corpus has no health check that could drive it: the only replica-facing check is `unihub_postgres_replica_lag_seconds` (`observability.md` §4), a debounced metric and not a routing input, and `db` Readiness covers the Master only. **TODO: design the health check that drives the routing fallback; only then can a test be written.** The fallback also moves all read traffic onto the Master — the load `architecture.md` §4.1 exists to keep off it.

### 2.6 Scenario 5 — S3 outage

- **Inject:** dedicated MinIO container, stopped and paused. MinIO, not LocalStack, is the S3 stand-in this corpus decided (`mocking-guidelines.md` §1; `workshop.md` ADR) — no second S3 emulator is introduced.
- **Expected:** the brochure flow degrades and nothing else. `POST /api/v1/workshops/{id}/brochure-upload-confirm` cannot complete its `HeadObject`, so **no `brochure_s3_key` is written** (PostgreSQL is authoritative, `architecture.md` §4.4); the summarization consumer fails into the retry chain with `ai_summary` left `NULL` (`workshop.md` §10). S3 unreachable → **`503`** problem `storage-unavailable` (decided 2026-10-30, Phase 5 checklist D60; the contract gains it with the endpoint). Issuing a presigned URL is expected to keep working (the SDK signs locally without calling S3) — verify, don't assume.
- **Must stay unaffected (probe, including free registration):** registration, payment charge (gateway stubbed), check-in sync, browsing — workshop detail returns `200` because `brochure_s3_key` is only a pointer.
- **INV:** INV-19 (`test-strategy.md` §2) — per-push `*IT` at Phase 5, the first phase in which Registration, Payment and Check-in all exist for the probe to assert against.

### 2.7 Scenario 6 — Audit pipeline outage (INV-14)

- **Inject:** dedicated RabbitMQ container stopped, so the fire-and-forget `unihub.system.audit.created` publish fails (`messaging.md` §3); variant: audit consumer failing into `unihub.audit.retry.*`. Stopping the broker also affects the outbox, but the assertion below holds a fortiori and the confound is accepted.
- **Expected — half one, assertable now:** a registration, a payment charge, and a check-in sync each return their normal success status; no audit rows exist for the outage window (the bounded gap `architecture.md` §5.5 accepts) and rows resume after recovery.
- **Expected — half two, blocked:** the publish failure must surface as an operational alert (`architecture.md` §5.5; `CLAUDE.md`). **`observability.md` §4's metric catalog and §6's alert table define no audit-publish signal, so there is nothing named to assert against, and none is invented here. TODO: define the signal in `observability.md`; until then INV-14's second clause has no test.**

### 2.8 Scenario 7 — Backend API pod kill (INV-16) — Phase 7, full topology only

Manual (or a dedicated pipeline), never per-push; it is a deployment smoke test, not a Testcontainers test.

- **Precondition:** `overlays/loadtest` (3 API replicas, `deployment-and-cicd.md` §3). `overlays/dev` runs one replica, where traffic continuity cannot be observed at all; `requirement.md` §3.3 asks for at least two.
- **7a — delete a pod** under probe traffic through the Ingress: assert the Deployment restores its replica count to Ready, and probe requests succeed throughout except a bounded burst for requests in flight on the killed pod. The manifest sets no `preStop` hook or termination grace (`deployment-and-cicd.md` §2), so a small burst is expected, not a defect. **TODO: the burst bound has no source.**
- **7b — unhealthy but running:** make a pod fail its probes without deleting it; assert it leaves Ingress rotation (Readiness, 10 s period) and is restarted (Liveness, 15 s period; `failureThreshold` is not set in the manifest, so the Kubernetes default applies — **TODO: verify**), and traffic continues on the others. **TODO: the mechanism is open** — sending `SIGSTOP` to PID 1 from inside the container is ignored by the kernel, so the freeze must come from the node (e.g. `minikube ssh`) or another means; a test-only liveness switch would need application code and is not proposed.

**Guarantees:** every scenario's expected outcome is traceable to the cited source line; where the corpus states none, the scenario says TODO rather than choosing.

---

## 3. Tooling

**Rationale:** the suite should add as little as possible to the stack `mocking-guidelines.md` and `integration-testing.md` already fixed, because `CLAUDE.md`'s Boundaries argue against new infrastructure beyond what the design specifies.

**Model:**

| Need | Mechanism | New dependency? |
|---|---|---|
| HTTP dependency faults (Payment Gateway) | WireMock — `withFixedDelay`, stopping the server (refused), `Fault.CONNECTION_RESET_BY_PEER`. Setup and rationale: `mocking-guidelines.md` §2 | No |
| Infrastructure faults (Redis, RabbitMQ, MinIO) | A **dedicated** container per fault test class — never the shared singleton, whose lifecycle (`integration-testing.md` §2) assumes it stays up for the whole JVM run; stopping it would fail every later test. The class points its own Spring context at the dedicated container through `@DynamicPropertySource`. *Refused:* `stop()`. *Hung:* Docker `pause`, via the docker-java client Testcontainers already exposes (`getDockerClient()`). *Restart with state kept (durability):* a docker-java restart, not Testcontainers `stop()`, which discards the container | No |
| Replica lag | `pg_wal_replay_pause()`/`pg_wal_replay_resume()` on the standby of the Master + replica fixture | No — the fixture is part of `integration-testing.md` §1–§2's stack |
| Graded slowness (e.g. Redis at 1.5 s, close to the seat lock's 2 s wait) | Toxiproxy `latency`/`timeout` toxics | **Yes — `org.testcontainers:toxiproxy` plus the `toxiproxy-java` client. Not adopted: no scenario above needs it, since stop/pause cover refused and hung.** Adopting it is the user's decision |
| Pod-level faults | `kubectl` against Minikube | No |
| Waiting for recovery | An explicit bounded wait, per the bounded-termination rule of `coding-standards.md` §4 | No |
| Selecting non-gating scenarios | A JUnit tag (e.g. `@Tag("resilience")`) excluded from `integrationTest`, run by a dedicated Gradle task. **`coding-standards.md` §1 and `deployment-and-cicd.md` §5 define only `test` and `integrationTest`; a third task is a new decision. TODO: confirm.** | A Gradle task |

**Guarantees:** scenarios 1, 2, 5, 6 add nothing to the per-push run beyond their own dedicated containers, and scenario 4 adds only the replica container of INV-03's fixture; nothing in this file requires a new dependency to be added without the user's say-so.

---

## 4. Guarantees

- **Fault isolation is proven by a concurrent probe, never by reading state alone** — `proposal.md` §12's Fault Isolation row is satisfied only if unrelated flows are shown succeeding *during* the fault.
- **Each dependency's specified direction is asserted** — fail-closed (Redis registration writes), fail-open (rate limiter, audit), skip (job lock), isolate (S3, Payment Gateway) — never a generic "still up".
- **At-least-once is respected** — no scenario asserts an absence of duplicate messages; only the absence of duplicate effects (INV-11) and of lost committed events (INV-10).
- **Nothing is asserted that the corpus doesn't state** — open behavior (Replica unreachable, S3-unreachable status code, audit alert signal) is recorded as TODO, not filled in.
- **Full topology is required only where stated** — scenarios 7, 1b and 2b; every other scenario runs on local Testcontainers.

---

## 5. Reference Table

| Item | Value | Section |
|---|---|---|
| Scenarios | 7 (plus 1b) — payment gateway, Redis, RabbitMQ, replica lag, S3, audit, pod kill | §2 |
| Needs Minikube | Scenarios 7, 1b and 2b only | §2.8, §2.2, §2.3 |
| Runs per-push (already a phase deliverable) | 1, 2, 4, 5, 6 | §2.1 |
| Fault mechanisms | WireMock (HTTP); dedicated container stop/pause/restart (infra); `pg_wal_replay_pause` (replica); `kubectl` (pods) | §3 |
| New dependency, not adopted | Toxiproxy | §3 |
| Needs a decision | A `resilience` Gradle task for the one on-demand scenario (3) | §3 |
| INV covered | INV-03, INV-05, INV-08, INV-10, INV-11, INV-14, INV-16, INV-19 (INV-09 skip side; INV-17's fail-open limit noted) | §1 |
| Open items | Probe latency multiplier; the Replica-unreachable health check; audit-publish alert signal; pod-kill burst bound and unhealthy-pod mechanism; WireMock stub placement | §2 |
