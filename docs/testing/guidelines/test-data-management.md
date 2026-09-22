# Test Data Management — UniHub Testing Guidelines

This file is the physical-level completion of the fixture/seed-data side of `mocking-guidelines.md` §3 (which owns the *time* mechanism — injected `Clock`, shortened TTL config) and of `test-design-techniques.md`'s catalogs (which specify *which* entity states and boundary values a test needs, but not how the row that carries them gets built). Nothing here overrides those two files — where this file and they appear to disagree, they are the authority and this file has a bug.

**Out of scope, and where it actually lives:** which technique a scenario comes from (`test-design-techniques.md`); the Clock/TTL-config mechanism itself for time-based scenarios (`mocking-guidelines.md` §3); test structure/naming once fixtures exist (`coding-standards.md`); Testcontainers container lifecycle itself — startup, reuse across classes (`../test-levels/integration-testing.md`).

---

## Table of Contents

- [1. Test Data Builders](#1-test-data-builders)
- [2. Baseline Defaults & Single-Field Override Discipline](#2-baseline-defaults-single-field-override-discipline)
- [3. Fixtures for Technical Pattern Entities](#3-fixtures-for-technical-pattern-entities)
- [4. Data Isolation Across Tests](#4-data-isolation-across-tests)
- [5. Uniqueness & Collision Avoidance](#5-uniqueness-collision-avoidance)
- [6. Reference Table](#6-reference-table)

---

## 1. Test Data Builders

**Rationale:** `coding-standards.md` §3 requires DAMP over DRY *inside a test body* — but building a `RESERVED` registration row means setting `workshopId`, `userId`, `status`, `holdExpiresAt`, `ticketCode` (null for a hold), and satisfying the DB's own CHECK constraints (`database-schema.md` §4.1) correctly, every time. Repeating that field-by-field in every test obscures a test's actual intent just as much as an over-abstracted helper would — DAMP applies to what a test is *proving*, not to how many `.set(...)` calls it takes to get a legal row into existence.

**Model:** one fluent builder per entity, living alongside its test-support code, defaulting every field to a legal baseline value:

```java
var registration = aReservedRegistration()
    .withHoldExpiresAt(fixedClock.instant().minusSeconds(1)) // the only field this test cares about
    .build();
```

| Rule | Convention |
|---|---|
| Builder scope | One builder per entity with a `status`/`outcome` column (`test-design-techniques.md` §2's six entities) — a builder is where "what does a legal `RESERVED` registration look like" is answered once |
| Builder naming | `a<State><Entity>()` static factory (e.g. `aReservedRegistration()`, `aPendingPayment()`, `aPublishedWorkshop()`) — the state is in the method name, not a parameter, so a test's Arrange line is self-describing without opening the builder |
| What a builder does NOT do | Decide the outcome under test, perform assertions, or encode business logic (e.g. a builder never internally checks "is this hold actually expired") — it only constructs a syntactically and constraint-legal row |

**Guarantees:** a builder producing a row that violates one of `database-schema.md`'s own CHECK constraints (e.g. a `RESERVED` row with `holdExpiresAt = null`, violating §4.1's `(status = 'RESERVED') = (hold_expires_at IS NOT NULL)`) fails at Testcontainers insert time — the builder itself is exercised against the real constraint on every use, not just the schema doc's prose description of it.

---

## 2. Baseline Defaults & Single-Field Override Discipline

**Rationale:** `test-design-techniques.md` §4 (Boundary Values) and §3 (Decision Tables) work by varying exactly one dimension while holding everything else at a known-valid baseline — if a builder's defaults are themselves inconsistent between tests, a boundary test can't tell whether a failure came from the boundary condition or from an unrelated default drifting.

**Model:** every builder's zero-argument baseline is a single, named "obviously valid" instance, documented once, reused everywhere:

| Entity | Baseline (`a...()` with no overrides) | Source |
|---|---|---|
| `aReservedRegistration()` | Valid workshop/user reference, `holdExpiresAt = now + 15 min`, `ticketCode = null` | `database-schema.md` §4.1 |
| `aPendingPayment()` | Valid registration reference, `initiatedAt = now`, `resolvedAt = null` | `payment.md` §1 |
| `aPublishedWorkshop()` | `capacity = 100` (this builder's own round-number choice — `workshops.capacity` has no schema `DEFAULT`, per `database-schema.md` §3.1, so any legal positive value is equally valid; 100 is picked only for readability), `seatsTaken = 0` (schema `DEFAULT 0`), `isPaid = false` (schema `DEFAULT false`) | `database-schema.md` §3.1 |
| `anAcceptedScan()` | Valid ticket, `scannedAt = now`, `outcome = ACCEPTED` | `checkin.md` §7 |
| `anActiveUser()` | Random `email` per build, `passwordHash` set — a directly-provisioned account, not a legacy-synced one (`legacyStudentId = null`), `fullName` set, `status = ACTIVE` (schema `DEFAULT 'ACTIVE'`) | `database-schema.md` §2.1 |
| `aRunningLegacySyncRun()` | `exportDate = today`, `status = RUNNING` (schema `DEFAULT 'RUNNING'`), `failureReason = null`, `startedAt = now`, `finishedAt = null`, all four row counters `= 0` (schema `DEFAULT 0`) | `database-schema.md` §2.6 |

A test that needs the seat-capacity boundary from `test-design-techniques.md` §4 overrides only `seatsTaken`:

```java
var full = aPublishedWorkshop().withCapacity(10).withSeatsTaken(10).build(); // must reject the next hold
var lastSeat = aPublishedWorkshop().withCapacity(10).withSeatsTaken(9).build(); // must accept exactly one more
```

**Guarantees:** two tests using the same builder's baseline and overriding different single fields never accidentally test different things because of an inconsistent default — the baseline table above is the one place defaults are decided.

---

## 3. Fixtures for Technical Pattern Entities

**Rationale:** `architecture.md` §6.1 names Idempotency Key, Event Publication, and Audit Event as cross-cutting Technical Pattern Entities, not business data — `CLAUDE.md` is explicit that they shouldn't be modeled like domain objects. Tests proving INV-02 (idempotency, same-transaction write), INV-10 (outbox atomicity), and INV-14 (audit fail-open) all need a legal row in one of these three tables, but none of them fit §1/§2's `a<State><Entity>()` pattern uniformly: only `event_publications` actually has a status column (`PENDING`/`PUBLISHED`); `idempotency_keys` and `audit_events` don't. This section is where the three get fixture guidance without forcing a builder shape onto the two that don't have a state to name.

**Model:**

| Entity | Fixture approach | Why it differs from §1/§2's pattern | Source |
|---|---|---|---|
| `idempotency_keys` | No `a<State>...()` builder — a plain factory taking `scope`, `idempotencyKey`, `responseStatus`, `responseBody` as **required arguments**, e.g. `anIdempotencyKeyRecord("PAYMENT_CHARGE", key, 200, storedBody)` | The entire point of an idempotency test is correlating a specific key with a specific prior request/response — defaulting these fields randomly (§1's usual "random by default" rule) would defeat the fixture's purpose, not just be irrelevant to it | `database-schema.md` §8.1 |
| `event_publications` | Has a status column, so it *does* follow §1/§2's convention: `aPendingEventPublication(aggregateType, aggregateId, eventType, payload)` / `aPublishedEventPublication(...)` | Fits the existing pattern directly — the one exception to this section needing special-case guidance | `database-schema.md` §8.2 |
| `audit_events` | No `a<State>...()` builder — a single baseline `anAuditEvent()` (valid `actorUserId` FK, `action`/`targetType`/`targetId` set, `occurredAt = now`) | No status/outcome column to name a state after; one baseline is enough since audit-fail-open tests (INV-14) care about whether the row gets written at all, not about a state machine on the row itself | `database-schema.md` §8.3 |

**A rule specific to `event_publications`:** for an outbox-atomicity test (INV-10), this fixture must be inserted as part of the *same* setup/transaction as the business row it announces — never built and inserted independently beforehand. The builder's job here is unchanged from §1's rule (produce a legal row, decide nothing about the outcome); the test itself owns pairing it with the business write it accompanies.

**Guarantees:** the same §1 rule applies to all three — none of these fixtures decides an outcome, performs an assertion, or encodes business logic; they only construct a row that satisfies the schema's own constraints (`database-schema.md` §8's `UNIQUE (scope, idempotency_key)`, the `PENDING`/`PUBLISHED` check on `event_publications.status`, and the `NOT NULL` columns on `audit_events`).

---

## 4. Data Isolation Across Tests

**Rationale:** `../strategy/test-strategy.md` §5 (Environment Strategy) reuses ephemeral Testcontainers per run, not per test — so nothing prevents one test's committed rows from being visible to the next test unless isolation is deliberate. Most tests want isolation; the anti-overbooking concurrency tests (`test-design-techniques.md` §3.1, INV-06) are the deliberate exception, and conflating the two isolation strategies breaks the exception.

**Model:**

| Test shape | Isolation mechanism | Why |
|---|---|---|
| Ordinary Integration test (single thread, asserts on its own writes) | Spring test-managed transaction, rolled back after each test (`@Transactional` on the test class) | Fast, automatic, no manual cleanup code needed |
| Concurrency tests (multiple threads/connections racing against the same row — INV-06, INV-07, INV-08, INV-09) | **Never** `@Transactional` rollback — each thread needs its own real, committed connection for the other threads to race against. Explicit setup (`@BeforeEach` insert) and teardown (`@AfterEach` delete) instead | Spring's rollback-per-test wraps the *whole test method* in one transaction; a second thread in that same test would see the first thread's uncommitted writes only if it shared the connection (defeating the point of a concurrency test) or would see nothing at all (equally wrong) if it used a separate connection. Concurrency correctness can only be proven against rows that are actually committed and visible across connections |
| Cross-transaction-boundary tests (outbox atomicity INV-10, reconciliation INV-04) | Explicit setup/teardown, same reasoning as concurrency tests — the property under test is specifically about what happens *across* transaction boundaries, so wrapping the test itself in one transaction would hide the boundary being tested | Same as above |

**Guarantees:** a test tagged against INV-06/07/08/09/10/04 in `../reports/test-matrix.md` uses explicit setup/teardown, never `@Transactional` rollback — this is checkable by inspection (absence of the annotation) during review, not just by trusting the test author remembered.

---

## 5. Uniqueness & Collision Avoidance

**Rationale:** several tables carry uniqueness constraints beyond the primary key — `registrations.ticket_code UNIQUE`, the partial index `UNIQUE (workshop_id, user_id) WHERE status IN ('RESERVED', 'CONFIRMED')` (`database-schema.md` §4.1) that stops a student holding two simultaneous active registrations for the same workshop, and `users.email UNIQUE` (`database-schema.md` §2.1). A builder that reuses a fixed value for these fields across tests produces flaky, order-dependent failures — a test passes alone and fails only when run alongside another test that happened to claim the same value first. `legacy_sync_runs` (added to §2 in D1) carries no uniqueness constraint beyond its primary key — its only indexes are on `export_date` and `status` — so it needs no row here.

**Model:**

| Field | Default generation | When to override |
|---|---|---|
| `ticketCode` | Random per build (e.g. UUID-derived, respecting `VARCHAR(64)`) | Never, unless the test specifically targets the `UNIQUE` constraint itself (in which case, building two rows with the same explicit `ticketCode` *is* the test) |
| `(workshopId, userId)` pair for an active registration | A fresh `userId` per build by default | When a test specifically targets the "no two simultaneous active registrations" partial-unique-index rule — build two `RESERVED`/`CONFIRMED` rows sharing both fields deliberately, and assert the second write is rejected |
| `anActiveUser()`'s `email` | Random per build (e.g. UUID-derived local part) | Never, unless the test specifically targets the `UNIQUE (email)` constraint itself — building two users with the same explicit `email` *is* that test |
| Any other builder field with a uniqueness constraint discovered later | Same rule: random by default, an explicit shared value only when the constraint itself is what's under test | Keeps the default path collision-free without needing a per-test-run reset of shared counters |

**Guarantees:** running the same Integration test class in parallel with itself (a different instance, same Testcontainers-backed DB, per `../strategy/test-strategy.md` §5's per-run-not-per-test container lifecycle) never fails on an accidental unique-constraint collision — only a test that deliberately constructs one, as its actual assertion, can trigger that constraint.

---

## 6. Reference Table

| Item | Value | Section |
|---|---|---|
| Builder naming | `a<State><Entity>()`, e.g. `aReservedRegistration()` | §1 |
| Baseline defaults | Fixed, documented once per entity | §2 |
| Technical Pattern Entity fixtures | Required-argument factory (Idempotency Key), state builder (Event Publication), single baseline (Audit Event) | §3 |
| Ordinary test isolation | `@Transactional` rollback | §4 |
| Concurrency/cross-transaction test isolation | Explicit setup/teardown — never `@Transactional` rollback | §4 |
| Collision-prone fields | Random by default (`ticketCode`, active-registration `(workshopId, userId)` pair, `anActiveUser()`'s `email`) | §5 |
