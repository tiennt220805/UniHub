# Test Design Techniques — UniHub Testing Guidelines

This file is the physical-level completion of `../strategy/test-strategy.md` §2's Non-Negotiable Invariant Catalog and `../strategy/test-plan.md`'s per-phase test deliverables, for the question those two files raise but don't answer: *how* is each test case actually derived from a requirement, rather than written ad hoc? Nothing here overrides `test-strategy.md`/`test-plan.md` — where this file and they appear to disagree, those two are the authority and this file has a bug.

**What this file is not:** a tutorial on what Equivalence Partitioning, Decision Table Testing, State Transition Testing, or Pairwise Testing *are*. That's textbook knowledge a test author (human or model) already has — re-deriving it here would be noise. What a technique's name alone can't supply is *which* technique fits *which* piece of UniHub's actual logic, and *where the concrete domain facts live* (the real boundary values, the real state set, the real condition combinations) — inventing those from the technique name alone produces plausible-looking but wrong test cases. That gap is this file's only job.

**Out of scope, and where it actually lives:** whether a given test needs Testcontainers or can mock (`mocking-guidelines.md`); fixture/seed-data mechanics for the scenarios below (`test-data-management.md`); which test level (unit/integration/contract/e2e) a derived case belongs in (`../test-levels/*.md`); the actual traceability rows once tests are written (`../reports/test-matrix.md`).

---

## Table of Contents

- [1. Technique-to-Logic-Shape Map](#1-technique-to-logic-shape-map)
- [2. State Transition Catalog](#2-state-transition-catalog)
- [3. Decision Table Catalog](#3-decision-table-catalog)
- [4. Boundary Value & Equivalence Partitioning Catalog](#4-boundary-value-equivalence-partitioning-catalog)
- [5. Pairwise Testing](#5-pairwise-testing)
- [6. Reference Table](#6-reference-table)

---

## 1. Technique-to-Logic-Shape Map

**Rationale:** each technique below answers a different question about a piece of logic. Applying the wrong one produces either too few cases (missing a real defect class) or a combinatorial explosion nobody will actually write (over-testing). The map exists so "derive test cases for this logic" has one obvious next question — *what shape is this logic* — instead of a blank page.

**Model:**

| Logic shape | Technique | Ask |
|---|---|---|
| An entity has a `status`/`outcome` column with a `CHECK IN (...)` constraint | State Transition Testing | Which transitions are reachable, and — more importantly — which ones must be *rejected*? A `CHECK` constraint validates a single value, never a sequence. |
| An outcome depends on **more than one** independent condition combined | Decision Table Testing | What are all the condition columns, and does every meaningful combination have a defined action — including the ones that should be impossible? |
| A field has a numeric/length/time bound fixed somewhere in the schema or contract | Boundary Value Analysis + Equivalence Partitioning | What's the fixed value, and what are the valid/invalid classes on either side of it? |
| Multiple **independent** dimensions combine, but a full cross-product is too large to justify writing every case | Pairwise Testing | Which pairs of dimension-values haven't been exercised together yet by the individual per-dimension tests above? |

Sections 2–5 below are the concrete catalog — every row cites the `technical-design/` source it was derived from, so a test author reads the source once and gets the test cases, rather than re-deriving domain facts from a technique name.

**Guarantees:** every test tagged against an INV-ID in `test-strategy.md` §2 should be traceable to one row in one of the catalogs below (via `../reports/test-matrix.md`). A reviewer should be able to ask "which technique produced this test, and where are the domain facts it's based on" and get a citation, not "it seemed reasonable."

---

## 2. State Transition Catalog

**Rationale:** `database-schema.md` §1 (Conventions) enforces every status column as `VARCHAR(n) NOT NULL CHECK (col IN (...))`, deliberately never a native `ENUM` (so a tolerant migration can add a value later, per `architecture.md` §6.2). A `CHECK` constraint only validates that a *single write* lands on an allowed value — it says nothing about whether the *previous* value made that transition legal. Every entity below needs its illegal transitions tested explicitly, or a bug that flips a terminal row back to an earlier state passes the schema silently.

**Model:**

| Entity (source) | States | Valid transitions | Must-reject transitions (illustrative, not exhaustive) | Related INV |
|---|---|---|---|---|
| `users.status` (`database-schema.md`) | `ACTIVE`, `SUSPENDED` | `ACTIVE → SUSPENDED` (confirmed) | `SUSPENDED → ACTIVE` (reactivation) is **not confirmed anywhere in the corpus** — flag as an open assumption to verify against `user.md` before writing this test, don't assume either direction | — |
| `workshops.status` (`database-schema.md`) | `DRAFT`, `PUBLISHED`, `CANCELLED`, `COMPLETED` | `DRAFT → PUBLISHED`; `PUBLISHED → CANCELLED`; `PUBLISHED → COMPLETED` | `COMPLETED → *` (terminal); `CANCELLED → *` (terminal); `PUBLISHED → DRAFT` (no "unpublish" path is described anywhere) | — |
| `registrations.status` (`database-schema.md` §4.1) | `RESERVED`, `CONFIRMED`, `CANCELLED`, `EXPIRED` | `RESERVED → CONFIRMED`; `RESERVED → CANCELLED`; `RESERVED → EXPIRED` (TTL lapse); `CONFIRMED → CANCELLED` | `EXPIRED → CONFIRMED` (a lapsed hold must never later convert — the seat it held may already be reassigned); `CONFIRMED → EXPIRED` (impossible per the `hold_expires_at` CHECK — `(status = 'RESERVED') = (hold_expires_at IS NOT NULL)` — a `CONFIRMED` row has no hold to expire); `CANCELLED → *` (terminal) | INV-06, INV-07 |
| `payments.status` (`payment.md` §1, §9) | `PENDING`, `SUCCEEDED`, `FAILED`, `CANCELLED`, `REFUND_REQUIRED`, `REFUNDED` | `PENDING → SUCCEEDED`; `PENDING → FAILED`; `PENDING → CANCELLED` (resolved by whichever of {webhook, reconciliation sweep} gets there first — §3 below — or by the breaker compensation when the breaker refused the call after the row was written, `payment.md` §3); `SUCCEEDED → REFUND_REQUIRED` (the payment succeeded after its registration stopped being `RESERVED` — conditional on `status = 'SUCCEEDED'`, Phase 2); `CANCELLED`/`FAILED → REFUND_REQUIRED` (**late success**: the gateway reports success for a payment UniHub had already closed — never a no-op, directly and without a `charge.succeeded` event, `payment.md` §6–§7; practically impossible with the create/confirm split, Phase 2 checklist D16, C33); `REFUND_REQUIRED → REFUNDED` (gateway refund confirmed — Phase 3) | `SUCCEEDED → FAILED` (no crossing between resolved terminal states); any state `→ PENDING` (no un-resolving); `CANCELLED`/`FAILED → REFUND_REQUIRED` **without** a gateway success report; `CANCELLED`/`FAILED → SUCCEEDED` (a late success is refunded, never re-opened as a live payment); `REFUNDED → *` (terminal); `REFUND_REQUIRED → SUCCEEDED` (a refund in progress is never undone) | INV-02, INV-04, INV-10 |
| `check_in_scans.outcome` (`checkin.md` §6–§7, `database-schema.md` §6.1) | `ACCEPTED`, `REJECTED_DUPLICATE`, `REJECTED_INVALID` | `ACCEPTED → REJECTED_DUPLICATE` — **the one deliberately non-monotonic transition in this system**: an already-`ACCEPTED` scan flips when an earlier-`scanned_at` scan from a different device syncs later (`checkin.md` §7) | `REJECTED_DUPLICATE → ACCEPTED` (reverse of the above — must never happen, since it would mean two devices both end up thinking they hold the winning scan); `REJECTED_INVALID → *` (never becomes valid after the fact — but this outcome covers **two distinct scenarios**, each from a different part of the corpus, and a test suite needs a case for each rather than treating them as one: (a) on-device signature-verification failure — forged/tampered payload, wrong/rejected algorithm, malformed input — rejected offline before the scan ever reaches the network, `checkin.md` §6; (b) a signature-*authentic* ticket whose registration was cancelled by the time the scan reconciles, `database-schema.md` §6.1's "authentic-but-not-valid gap") | INV-12 |
| `legacy_sync_runs.status` (`database-schema.md`) | `RUNNING`, `COMPLETED`, `FAILED` | `RUNNING → COMPLETED`; `RUNNING → FAILED` | `RUNNING → RUNNING` (a second concurrent run starting while one is already `RUNNING` — this is exactly what the job-exclusivity lock exists to prevent) | INV-09, INV-13 |

**Guarantees:** the `ACCEPTED → REJECTED_DUPLICATE` row above is the catalog's most important entry — it is the only transition in this system where a row's outcome legitimately changes after being set, and a naive "outcome is write-once" assumption in the implementation would pass every other entity's tests while silently breaking check-in reconciliation.

---

## 3. Decision Table Catalog

**Rationale:** these are the pieces of UniHub logic where the *combination* of conditions — not any single one — decides the outcome. Testing each condition independently ("does it work when the lock succeeds" / "does it work when the version matches") is necessary but not sufficient; the interaction between conditions is exactly where the layered invariants in `CLAUDE.md` are designed to hold even when one layer alone wouldn't.

**Model:**

**3.1 — Anti-overbooking (INV-06, `architecture.md` §4.2/§6.1, `proposal.md` §11.1):**

**A note before the table, because this is easy to misread:** the "lock step skipped" rows below are a **defense-in-depth** test — the lock's *acquisition step itself* is never called (a stand-in for an application bug that bypasses it), while Redis remains fully reachable. This is a different question from *"what if Redis is actually unreachable"* — that's a fail-safe/availability question, not a data-race question, and `architecture.md` §4.2 already answers it: an unreachable coordination layer means the request is **rejected outright**, never allowed to fall through to the DB uncoordinated (INV-08). That fail-safe behavior is a fault-injection scenario, out of scope for a decision table shaped around lock/version/constraint state — it belongs to `../non-functional/resilience-testing.md`, referenced here, not re-derived. Do not read the rows below as "Redis is down and the DB catches it anyway" — that reading contradicts INV-08; the correct reading is "the lock step never ran, and the DB still caught it."

| Redis lock acquired? | Optimistic version matches? | DB check constraint satisfied? | Outcome |
|---|---|---|---|
| Yes | Yes | Yes | Accept |
| Yes | No (concurrent writer won) | — | Reject — retry or 409, caller's choice |
| No (`tryLock` wait exhausted — lock contention) | — | — | Reject — `503` + `Retry-After` (`registration.md` §2), no DB round-trip attempted. **Not `409`**: `409` means "lock acquired, workshop actually full" and is a different, permanent-for-this-attempt fact |
| N/A — lock acquisition step skipped by the test itself, Redis otherwise reachable (defense-in-depth case, see note above) | Yes | Yes | Accept — proves the DB layer alone still holds even if the application never called the lock at all |
| N/A — lock acquisition step skipped by the test itself, Redis otherwise reachable | Yes (attempted bypass at raw SQL level) | No | Reject — the check constraint is the true final backstop |

**How the "lock step skipped" rows are realised (Phase 3 checklist D23, 2026-10-26):** a no-op `SeatLock` registered by a `@TestConfiguration` (`@Primary`) in the `integrationTest` source set only. Built as `RegistrationConcurrencyLockSkippedIT` (the full-stack variant is `RegistrationConcurrencyIT`, the DB-only variant `SeatCheckConstraintIT`). Two guards keep it from passing vacuously: the real Redisson adapter must have no interaction at all, and version conflicts must have been counted (a test-only barrier on the claim makes the first claims share a snapshot, so the race is certain rather than likely). Note the global Micrometer registry has no backing registry until a test attaches a `SimpleMeterRegistry`; without one the counters read 0 whatever happened. It is never a production property or profile — a switch that turns the seat lock off in production would weaken INV-08. An architecture rule asserts that no class under `src/main` implements `SeatLock` other than the Redisson adapter, and the test asserts the lock was acquired zero times while Redis stayed reachable.

This table **is** `test-plan.md` Phase 3's three test variants (full-stack, lock-disabled, DB-only) — those three integration tests are this table's three "lock" columns held constant while the row varies. Read them together, not as unrelated tests. The fourth variant implied by INV-08 (Redis genuinely unreachable) is a separate resilience test, not a fourth row here — see the note above.

**Interpretation for the adversarial scenario (500 VUs vs. the last 10 seats — `../non-functional/performance-load.md` §2.2).** `proposal.md` §12 requires every loser to receive "a clear, immediate 'unavailable' response — no silent failures", and `requirement.md` §3.2 names `409` or "an accurate 'sold out' message" for requests arriving afterward. This project reads both as satisfied by the two-response contract of `registration.md` §2: a request that reaches the seat check and finds the workshop full gets `409`; a request that loses lock contention gets `503` with `Retry-After` within the bounded 2-second `tryLock` wait — and, retried with the same `Idempotency-Key` until it gets a definitive answer, ends at `409` (a `503` is never recorded in `idempotency_keys`, so the retry is a fresh attempt, `registration.md` §4). Neither response is silent, and no request is left undecided. "Immediate" therefore means *bounded and explicit*, not *instantaneous* — a `503` can take up to the lock wait. Consequence for assertions: a loser's **first** response is classified `{409, 503 + Retry-After}` and never asserted as all-`409`; the **converged** outcome is exactly the remaining seats as successes and everything else `409`. Approved in review; the interpretation lives here, not in `requirement.md`, because it reads the SRS rather than amending it.

**3.2 — Payment resolution race (INV-04, INV-10, `payment.md` §6–§7):**

| Webhook arrives | Sweep arrives | Result |
|---|---|---|
| First | — | Webhook resolves the row; sweep's later `status <> 'PENDING'` re-check makes it a no-op |
| — | First | Sweep resolves the row; webhook's later re-check makes it a no-op |
| Simultaneously (both read `PENDING` before either writes) | Simultaneously | The `version` column's optimistic check lets exactly one writer win; the loser's `UPDATE ... WHERE version = :expected` matches zero rows and no-ops — never a double-resolve |

**3.3 — Circuit breaker × call-site (INV-05, `payment.md` §2, §4, §9):**

| `paymentGateway` breaker state | Call-site | Behavior |
|---|---|---|
| `CLOSED` | Sync charge / Reconciliation sweep / Webhook key-fetch | Call proceeds normally |
| `OPEN` | Sync charge | Fail fast — breaker checked **before** the first transaction: no `payments` row, no idempotency key, existing `RESERVED` hold left untouched (student retries within their remaining hold window, same key executes for real) |
| Flips to `OPEN` between the charge's first transaction and the call | Sync charge | Compensating transaction: payment `PENDING → CANCELLED`, idempotency key deleted, `503` (`payment.md` §3) — never left `PENDING`, never recorded |
| `OPEN` | Reconciliation sweep | Skip this payment for the current cycle — it still matches the sweep's `WHERE` predicate next cycle, retried against the breaker's then-current state |
| `HALF_OPEN` | Any | One trial call decides whether the shared breaker re-closes or re-opens — all three call-sites observe the same decision, never an independent one per call-site |

**3.4 — RBAC evaluation (INV-01, `requirement.md` §2.11):**

| Has required permission? | Resource exists? | Resource state allows visibility to this caller? | Response |
|---|---|---|---|
| No | — | — | `403` |
| Yes | No | — | `404` |
| Yes | Yes | Yes | `200` |
| Yes | Yes | No (e.g. `DRAFT` workshop, unauthenticated/under-permissioned caller) | `404` — not `403`, so resource existence isn't leaked to a caller who shouldn't know it exists (already fixed as the contract's own rule — `packages/api-contract/paths/workshop.yaml`'s `GET` detail operation: *"the workshop is `DRAFT` and the caller... isn't permitted to see it"* → `404`, not `403`) |

**Guarantees:** a change to any one condition column above without re-running every row is not a complete test of that rule — these tables exist precisely because per-condition testing alone would miss the cells that matter most (the "N/A" and "simultaneously" rows).

---

## 4. Boundary Value & Equivalence Partitioning Catalog

**Rationale:** every bound below is already a fixed decision elsewhere in the corpus (a `VARCHAR(n)`, a `maxItems`, a 15-minute TTL) — testing "around" a value means testing the exact fixed number, not an invented one. A test written against a guessed bound silently stops being correct the day the real bound changes.

**Model:**

| Field / bound | Fixed value | Equivalence classes to test | Source |
|---|---|---|---|
| `registrations.ticket_code` | `VARCHAR(64)` | 64 chars (valid, boundary) / 65 chars (reject) / 1 char (valid, other boundary) / empty (reject, if nullable-vs-empty is itself a distinct rule) | `database-schema.md` §4.1 |
| `check_in_scans.device_id` | `VARCHAR(100)` | 100 (valid) / 101 (reject) | `database-schema.md` §6.1; mirrored directly as `deviceId`'s `maxLength: 100` in `packages/api-contract/schemas/checkin.yaml` |
| `workshops.title` / `speaker_name` / `location` | `VARCHAR(255)` | 255 (valid) / 256 (reject) | `database-schema.md` §3.1 |
| `SyncScanBatchRequest.scans` | `minItems: 1`, `maxItems: 500` | 0 items (reject) / 1 item (valid, boundary) / 500 items (valid, boundary) / 501 items (reject) | `packages/api-contract/schemas/checkin.yaml` — flagged in that file itself as "maxItems value inferred... not specified in `checkin.md` §7" |
| Seat-hold TTL (`registrations.hold_expires_at`) | 15 minutes | Just under 15:00 elapsed (still `RESERVED`, not yet swept) / exactly at 15:00 / just past 15:00 (must be `EXPIRED` on next sweep tick) — via injectable `Clock`, see `mocking-guidelines.md` §3 | `requirement.md` §1.3.1, INV-07 |
| Payment reconciliation threshold (`payments.initiated_at`) | 15 minutes | Same three-point boundary as above, same mechanism | `payment.md` §6, INV-04 |
| Seat capacity (`workshops.capacity`/`seats_taken`) | Per-workshop, caller-defined | `seats_taken = capacity - 1` (last seat available — must accept exactly one more) / `seats_taken = capacity` (must reject) / an attempted write that would push `seats_taken > capacity` (must be physically impossible, not just rejected — the check constraint's job) | INV-06, `proposal.md` §11.1 |
| Rate limit — per-user registration attempts (`unihub:ratelimit:registration:{userId}`) | 5 requests / 10s | 4th request in the window (accept) / 5th request in the window (accept, boundary) / 6th request in the same window (reject, `429`) / 1st request after the window rolls over (accept again) | `distributed-coordination.md` §3, INV-17 |
| Rate limit — general API traffic, keyed by user when the token is valid (`unihub:ratelimit:global-user:{userId}`), by address otherwise, invalid tokens included (`unihub:ratelimit:global-ip:{ipAddress}`); the payment webhook is exempt (Phase 7 checklist D90) | 100 requests / 60s | 99th request in the window (accept) / 100th request in the window (accept, boundary) / 101st request in the same window (reject, `429`) / 1st request after the window rolls over (accept again) | `distributed-coordination.md` §3, INV-17 |

**Guarantees:** every VARCHAR/maxItems/TTL bound tested this way stays synchronized with `database-schema.md`/`packages/api-contract` by construction — if a future schema change moves a bound, the corresponding test (asserting the *old* boundary) fails immediately instead of silently testing a stale limit.

---

## 5. Pairwise Testing

**Rationale:** unlike §§2–4, nothing in this project's scale currently demands full combinatorial tooling (e.g. a pairwise-generation library) — `proposal.md` §16 frames this as a single-developer PoC, and the condition spaces below are small enough to hand-pick a representative subset rather than adopt combinatorial-test tooling as a new dependency. This section is scoped narrowly on purpose.

**Model:** two dimension-sets worth deliberately combining, beyond what §§2–4 already cover per-dimension:

| Dimensions | Why pairwise, not full cross-product | Representative pairs worth hand-picking |
|---|---|---|
| `workshops.is_paid` (Y/N) × registration path (immediate-confirm vs. hold-then-pay) × `paymentGateway` breaker state (CLOSED/OPEN) | Each dimension is already covered alone (§3.1/§3.3); the interaction of "free workshop while the payment breaker happens to be OPEN" (should be completely unaffected — free path never touches the gateway) is the case most likely to be missed by testing the dimensions separately | `is_paid=false` × any breaker state (must all behave identically — proves the free path has zero coupling to payment); `is_paid=true` × `OPEN` (already covered in §3.3, cross-referenced here rather than duplicated) |
| Caller's permission set × endpoint × target resource's visibility state (`DRAFT`/`PUBLISHED`) | §3.4 already covers the single-resource RBAC decision table; the risk here is a permission check applied at the wrong layer for one specific endpoint (e.g. list endpoints silently leaking `DRAFT` rows through a filter that forgot the visibility condition) | For each list/search endpoint specifically (not just detail-by-id, which §3.4 already covers): under-permissioned caller × workshop mix containing both `DRAFT` and `PUBLISHED` rows — assert zero `DRAFT` rows leak into the response |

**Guarantees:** pairwise coverage here is a judgment call recorded once (this table), not re-derived per test-writing session — a test author picks from this table instead of deciding from scratch whether a given interaction is worth a dedicated case.

---

## 6. Reference Table

| Catalog | Count | Section |
|---|---|---|
| State Transition entities catalogued | 6 | §2 |
| Decision tables catalogued | 4 | §3 |
| Boundary/equivalence bounds catalogued | 9 | §4 |
| Pairwise dimension-sets catalogued | 2 | §5 |
