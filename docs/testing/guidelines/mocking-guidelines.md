# Mocking Guidelines — UniHub Testing Guidelines

This file is the physical-level completion of the "Testcontainers?" column in `../strategy/test-strategy.md` §2's Non-Negotiable Invariant Catalog, and of the Clock-injection/shortened-TTL mechanics `../strategy/test-plan.md` forward-references for INV-04 and INV-07. Nothing here overrides those two files — where this file and they appear to disagree, they are the authority and this file has a bug.

**Out of scope, and where it actually lives:** which functional test-design technique produces which test case (`test-design-techniques.md`); fixture/seed-data construction (`test-data-management.md`); Testcontainers container-lifecycle mechanics (reuse, startup ordering) once written (`../test-levels/integration-testing.md`); which test level a given case belongs in (`../test-levels/*.md`).

---

## Table of Contents

- [1. Real-vs-Mock Decision Rule](#1-real-vs-mock-decision-rule)
- [2. Mocking by Test Level](#2-mocking-by-test-level)
- [3. Clock Injection & Shortened TTLs](#3-clock-injection-shortened-ttls)
- [4. Reference Table](#4-reference-table)

---

## 1. Real-vs-Mock Decision Rule

**Rationale:** `test-strategy.md` §2 already marks each invariant Testcontainers `Y`/`N` — this section is the general rule that produced those marks, stated once so a new invariant discovered later gets classified consistently instead of argued from scratch. The rule follows the project's own preference order (real implementation over fake over stub over mock), specialized by *why* a given dependency can or can't be real in a test: infrastructure this system depends on for the correctness properties under test must be real; third-party systems this system doesn't own or control never can be, regardless of how important the test is. **"Always" in the table below means "always at the Integration level"** — it is not a claim that Unit tests also stand up Testcontainers; §2 states the Unit-level counterpart explicitly so this table isn't read as contradicting `coding-standards.md`'s "`./gradlew test` never touches Testcontainers" guarantee.

**Model:**

| Dependency | Real in tests? | Mechanism | Why |
|---|---|---|---|
| PostgreSQL | Always, at Integration level | Testcontainers | Transaction isolation and locking behavior are exactly what `requirement.md` §3.2 requires tests to prove — an in-memory substitute (H2) is explicitly forbidden by that same requirement. The same holds for replication lag (INV-03): a mock cannot make a replica genuinely stale, so INV-03's tests use a real streaming replica (`../test-levels/integration-testing.md` §1–§2) |
| Redis | Always, at Integration level | Testcontainers | Distributed-lock semantics (INV-06, INV-08, INV-09) are advisory-layer behavior a mock can't reproduce under real contention |
| RabbitMQ | Always, at Integration level | Testcontainers | At-least-once redelivery (INV-11) and outbox-relay timing (INV-10) are broker-specific behaviors |
| AWS S3 | Always, at Integration level | Testcontainers (MinIO's S3-compatible API) | Unlike Payment Gateway/AI Summarization Service below, S3 isn't a third party whose timing this system doesn't control — `architecture.md` §4.4 documents S3 as strong-read-after-write and deterministic, and names its own fault-isolation guarantee ("an Object Storage outage... never cascades into unrelated modules"). Both properties — the upload-before-fetch ordering and the outage-isolation boundary — need a real, faithfully-behaving S3 API to prove, not a hand-rolled HTTP stub guessing at S3's actual semantics. MinIO, not LocalStack, is the concrete choice — it's what the real CI pipeline already runs (`deployment-and-cicd.md` §5's `integration-test` job), so this table follows that job rather than naming a different tool here |
| Payment Gateway (Stripe sandbox) | Never | HTTP stub (§2) | External, not owned by this system, not open-source/containerizable; real sandbox calls are rate-limited, slow, and — per `proposal.md` §16's own risk table — don't reliably reproduce production timing, making them a *worse* source of truth for a circuit-breaker test (INV-05), not just a slower one |
| AI Summarization Service | Never | HTTP stub (§2) | Same reasoning — external dependency exclusive to the Workshop module |
| Legacy Student Management System | Never | HTTP/file stub (§2) | Same reasoning — external, nightly-sync source (INV-13) |
| Notification Providers | Never | Email over SMTP: **Mailpit** container (§2); an HTTP provider, if one is ever added: HTTP stub (§2) | Same reasoning — external, Notification module's exclusive dependency. Mailpit is a test double (an SMTP catcher with an HTTP API to read what was sent), in the same role as WireMock, not a production component (Phase 4 checklist D36) |
| Intra-monolith module calls (e.g. Registration calling Workshop for capacity) | Always real | Direct Java call, nothing to stub | `CLAUDE.md`'s modular-monolith framing means these are in-process method calls, not network calls — there is no boundary here to mock at the integration level (a Unit test may still mock the Java interface for isolation, per §2) |

**Guarantees:** no test in this suite — including the Phase 9 full-topology load test — ever makes a real network call to Stripe, the (separate) AI Summarization Service, the legacy system, real AWS S3, or a notification provider. This holds even at full scale: a 12,000-CCU load test hammering a *live* Stripe sandbox (or a real S3 bucket) would both violate usage terms/incur real cost and make results non-reproducible, which defeats the point of a load test in the first place.

---

## 2. Mocking by Test Level

**Rationale:** the same dependency (say, the Payment Gateway, or Redis) needs a different mocking mechanism depending on what the test is actually proving — `test-plan.md` Phase 2 lists both "unit tests for payment orchestration" (no infrastructure at all) and an integration test for the circuit breaker (INV-05, real Redis/RabbitMQ but a fake gateway). Conflating these into one mechanism either over-mocks the integration test or under-isolates the unit test. **This split is general, not specific to external HTTP dependencies:** it applies to every dependency §1 marks as Testcontainers-backed — Postgres, Redis, RabbitMQ, and S3 alike — not only Payment Gateway/AI Summarization/Legacy SMS/Notification Providers. §1's own Intra-monolith row already says this in passing ("a Unit test may still mock the Java interface for isolation, per §2"); this section is where that general rule actually lives.

**Model:**

| Test level | What's mocked | Mechanism |
|---|---|---|
| Unit (`../test-levels/unit-testing.md`) | The module's own Java interface for whichever dependency the logic under test talks to — a `PaymentGatewayClient` interface, but equally a `RedissonClient`/`RLock` for lock-timeout branching, a `RabbitTemplate`/`AmqpTemplate` for publish-failure branching, or an `S3Client` for upload-failure branching | Mockito — returns canned success/failure objects; proves the orchestration logic's own branching, nothing about the network or the real infrastructure behind the interface |
| Integration (`../test-levels/integration-testing.md`) | Only the external HTTP boundary for third-party services — Postgres/Redis/RabbitMQ/S3 all stay real (§1), including for tests that aren't primarily "about" one of them | WireMock (embedded `WireMockServer`, or the `org.testcontainers:wiremock` module alongside the other Testcontainers already in play) standing in for the Payment Gateway/AI Summarization Service/Legacy SMS/Notification Provider HTTP endpoint. The email channel speaks SMTP, not HTTP, so its double is a **Mailpit** container (Testcontainers `GenericContainer`, image pinned to an exact version), read through Mailpit's HTTP API; a failure SMTP cannot be made to produce on demand (a per-recipient 5xx, an authentication refusal) is proven by a Unit test of the channel's error mapping instead (Phase 4 checklist D36). This is this document's own tool decision — not named in `CLAUDE.md`'s tech stack — flagged explicitly as such, the same way `distributed-coordination.md` fixes concrete tool choices (e.g. Redisson's `SingleServerConfig`) beyond what `CLAUDE.md` itself specifies |
| Contract / E2E / Load | N/A — these levels don't reach into a single module's internals | Same WireMock stub layer carries forward wherever an external call would otherwise fire, including at Phase 9 full-topology scale (§1's Guarantee). On the Phase 7 cluster (checklist D86): WireMock deployments for the payment gateway and the AI service, with mappings committed under `k8s/base/stubs/` and taken from what the integration tests already stub (create, confirm, retrieve and cancel a PaymentIntent; list and create a refund) — the retrieve answer matches the confirm answer; no signed webhook, the webhook secret is left unset so webhooks are refused; Mailpit for email. Written 2026-11-05 (task 5.2); the ids come from the `Idempotency-Key` the adapter sends (response templating), so a replayed create answers the same PaymentIntent, and `StripeStubMappingsIT` runs the real `StripePaymentGateway` against exactly that file, in the image and with the arguments of the committed Deployment, so a call the stub stops answering fails in CI rather than in a pod |

**Guarantees:** an integration test proving INV-05 (circuit breaker opens against a slow/dead gateway) configures a WireMock stub with an injected delay or connection-refused fault — a deterministic, repeatable condition — rather than depending on the real sandbox's actual current behavior, which `proposal.md` §16 already flags as unreliable for exactly this purpose. A unit test proving, say, "registration fails gracefully when the lock client throws" mocks `RedissonClient` directly — it never needs a Testcontainers Redis to prove a branch that never actually calls Redis for real.

---

## 3. Clock Injection & Shortened TTLs

**Rationale:** INV-04 (payment stuck `Pending` past 15 minutes) and INV-07 (seat hold past 15 minutes) are the two invariants `test-plan.md` §1 explicitly forward-references to this file — neither can be proven by a test that actually waits 15 minutes.

**Model:** two complementary mechanisms, not alternatives:

1. **Injected `Clock`.** Wherever business logic reads "now" for a TTL comparison (`hold_expires_at`, `initiated_at`), it takes a Spring-managed `java.time.Clock` (defaulting to `Clock.systemUTC()` in production) rather than calling `Instant.now()`/`LocalDateTime.now()` directly. A test supplies `Clock.fixed(...)` to simulate an instant 15-minutes-and-one-second later, proving the comparison logic itself — fast, fully deterministic, no scheduler involved.
2. **Shortened TTL config.** Where the 15-minute value is sourced from a configuration property rather than hard-coded, a test profile overrides it to a much smaller real value (e.g. 2 seconds). Combined with the real scheduled-job trigger (still running on its normal cadence), this proves the *end-to-end* path — the sweep/expiry job actually fires and picks the row up — not just the comparison logic in isolation.

Use (1) for a fast, isolated proof that the boundary condition itself is correct (§4 of `test-design-techniques.md`'s TTL boundary rows). Use (2) when the test also needs to prove the scheduled job's own trigger path is wired correctly, not just the condition it evaluates.

**Guarantees:** no test in this suite sleeps for a real 15 minutes. A test that does is a bug in the test, not an acceptable slow test — `test-strategy.md` §4's Integration tier is expected to run in CI on every push, and a 15-minute sleep would violate that on its own regardless of correctness.

---

## 4. Reference Table

| Item | Value | Source |
|---|---|---|
| Always-real infrastructure (Integration level) | PostgreSQL (Master, plus a streaming replica for INV-03), Redis, RabbitMQ, AWS S3 (via MinIO) — always Testcontainers | §1 |
| Never-real dependencies | Payment Gateway, AI Summarization Service, Legacy SMS, Notification Providers (always stubbed, every level, every phase) | §1 |
| External-boundary stub tool | WireMock (embedded or `org.testcontainers:wiremock`) | §2 |
| Unit-level mocking | Mockito on the module's own client interface — the external dependency's own SDK client (`PaymentGatewayClient`) or the infrastructure client itself (`RedissonClient`, `RabbitTemplate`, `S3Client`) | §2 |
| Time-based test mechanism | Injected `Clock` (comparison logic) + shortened TTL config (end-to-end job path) | §3 |
