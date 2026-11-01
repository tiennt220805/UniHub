# Phase 3 Sign-Off — Concurrency Protection

Copy of `phase-template.md` §2–§6 for Phase 3. Bare filenames follow that template's citation convention (`test-plan.md`, `test-strategy.md` in `../../strategy/`; `test-matrix.md` in `../`).

**Status: signed — Phase 3 complete.** Every box in §3 and §4 is ticked against evidence that was observed, every deferral is in §5, and §6 was signed by Nguyễn Thành Tiến on 2026-10-28. Nothing in this file is evidence of a result that was not observed; each cell says what was run and where.

Prepared 2026-10-28 from `checklists/phase-3-concurrency-protection.md`.

---

## Conditions that stood in the way of signing — all settled

| # | What | State |
| --- | --- | --- |
| B1 | **CI must be green on one commit.** Phase 3 added tests that had never run on CI together (the seat-hold expiry and refund tests of Mục 3 first ran on CI with Mục 4). | **Settled 2026-10-28.** `98dc206` was red twice on one Mục 3 test, `SeatHoldExpiryIT#expiry_doesNotBlockRegistration` (its self-imposed cap of 380 registrations is reached on a faster runner before the 4 s hold ends; locally 184–254 fit). Fixed in `59e4f4c` (green, 8 jobs). The last code change (Mục 6, web) is `95af2a3`; `3c8111e` (the commit in §2) carries it and is green in all 8 jobs. |
| B2 | **M-78 (a Phase 2 row) was touched by Phase 3** (`CheckoutPage` now follows a payment through the refund). | **Settled 2026-10-28:** `Drafted` while the new tests had not run on CI, `Passing` again on the green `web` job of `3c8111e`. |

---

## 2. Header Fields

| Field | Value |
| --- | --- |
| Phase (1–8, `proposal.md` §15 / `test-plan.md` §1) | 3 |
| Phase name | Concurrency Protection |
| Date | 2026-10-28 |
| Commit SHA the checklist was run against | `3c8111e` — CI run 40648810191: `lint`, `unit-test`, `integration-test`, `contract-diff`, `web`, `secret-scan-gitleaks`, `sast-semgrep`, `dependency-check` all `success`. The commits after `59e4f4c` change the K6 files, the web checkout page and documentation only |
| INV IDs in scope (the INV IDs column of this phase's row in `test-plan.md` §3) | INV-06, INV-07, INV-08, INV-09 |
| Self-certified by | Nguyễn Thành Tiến |
---

## 3. Exit Criteria

Copied verbatim from the **Exit criteria** cell of `test-plan.md` §1, Phase 3 row — four semicolon-separated claims, four rows.

| # | Exit criterion (copy from `test-plan.md` §1, this phase's row) | Evidence | Met |
| --- | --- | --- | --- |
| 1 | Local (non-K8s) Testcontainers tests demonstrate 0% overbooking through each protection layer independently — full stack, lock-disabled, and DB-only (INV-06) — before this is trusted at full-topology load (Phase 8) | M-28 … M-32 `Passing`. Full stack: `RegistrationConcurrencyIT#acceptsExactlyRemainingSeats_whenConcurrentRequestsExceedCapacity` and `#acceptsExactlyRemainingSeats_whenConcurrentPaidRequestsExceedCapacity` (40 students over real HTTP vs. 3 seats; exactly 3 accepted; SQL: `seats_taken` = capacity = rows holding a seat; the Redisson lock was asked for and 0 version conflicts were counted). Lock-disabled: `RegistrationConcurrencyLockSkippedIT#acceptsExactlyRemainingSeats_whenTheLockStepIsSkipped` (+ `_paid`; the real lock has 0 interactions with Redis reachable, version conflicts counted > 0 so the layer demonstrably did the work). DB-only: `SeatCheckConstraintIT` (3; 25 raw JDBC writers, every refusal is SQLSTATE `23514`). Isolation: `SeatClaimIsolationIT` (4, `read committed` read inside the claim's own transaction). Two instances: `SeatLockMultiInstanceIT#redisson_lastSeat_noTwoHoldsOverlap` and the control `#control_inProcessLocks_overlap`. Mutations checked by hand: a no-op `RedissonSeatLock` turns M-28 red; a claim `UPDATE` without its `version` and capacity predicates turns M-29 red | ☑ |
| 2 | Redis-outage test proves writes are rejected, never uncoordinated (INV-08) | M-34 `Passing`: `RedisOutageIT` (`fault=STOPPED`, `fault=PAUSED`: 8 concurrent registrations all `503` + `Retry-After`, nothing written, catalog/detail/webhook `200`, a job tick skipped, recovery without a restart), `RedissonSeatLockIT`, `RedissonSeatLockContractIT`. Mutation (let `RedissonSeatLock` proceed without a lock on a Redis error) turns both variants red. Two probes the scenario names do not exist yet and are N/A, see §5 row 3 | ☑ |
| 3 | seat-hold release test proves automatic release with no blocked request (INV-07) | M-33 `Passing`: `SeatHoldExpiryIT` (real delay queue with a 4 s hold: released by itself, the seat sells again; other registrations not blocked, slowest < 2 s, across the release; redelivered 3× → one release; `seats_taken` equals the `RESERVED` + `CONFIRMED` rows after every step; confirm ↔ expire race), `SeatHoldExpiryRedisOutageIT` (Redis away for many times the retry chain: never parked, seat returned afterwards), `ExpireSeatHoldTest`, `ExpireSeatHoldTransactionTest`, `RetryChainRecovererTest`. M-76 `Passing` (the refund this branch triggers): `RefundIT`, `RefundEndToEndIT`, `RequireRefundIT` | ☑ |
| 4 | one-instance test proves no duplicate job execution across parallel instances (INV-09) | M-35 `Passing`: `JobSingleInstanceIT#redisson_noTwoRunsOverlap` (two contexts, a `RedissonClient` each, a real scheduled job, 0 overlapping holds, both instances ran it) and the control `#control_inProcessLocks_overlap`; not based on the seat-hold test (checklist C1) | ☑ |

---

## 4. Universal Checklist

| # | Check | How verified | Done |
| --- | --- | --- | --- |
| U1 | Every INV in scope has Status `Passing` in `test-matrix.md` | INV-06: M-28, M-29, M-30, M-31, M-32 · INV-07: M-33 · INV-08: M-34 · INV-09: M-35 — all `Passing` (CI green on `3c8111e`). M-36 carries INV-06 as a smoke row and is `Drafted` by design: the gate for INV-06 is M-28 … M-32 (`test-plan.md` §1: the K6 script "is **not** the evidence gate"); see §5 row 1 | ☑ |
| U2 | Every other matrix row of this phase is `Passing`, or is listed in §5 with a reason. No row is `Flaky — investigate` | M-76 `Passing`. M-36 `Drafted`, listed in §5 row 1. No row is `Flaky`; the Phase 3 concurrency ITs were repeated 10 times in a row locally under JDK 21 (13–15 tests per run), 0 failures | ☑ |
| U3 | Coverage ≥80% on the core business-logic services in this phase's scope | `jacocoTestCoverageVerification` is part of the `unit-test` job, green on `3c8111e`; it covers the new use cases `ExpireSeatHold`, `ExpireSeatHoldTransaction`, `RefundPayment`, `RefundPaymentSteps` | ☑ |
| U4 | Every CI stage that exists at this phase is green on the commit in §2 | The 8 jobs listed in §2, run 40648810191. `sast-semgrep` and `dependency-check` are non-blocking | ☑ |
| U5 | No unresolved `TODO`/`FIXME` in this phase's test code | `grep -rnE 'TODO\|FIXME' apps/backend/src/test apps/backend/src/integrationTest apps/web/src apps/web/vite-plugins tests/load`: zero hits (2026-10-28) | ☑ |
| U6 | No test backing an INV of this phase is skipped or weakened | `grep -rnE '@Disabled\|\.skip\(\|it\.todo\|xit\(\|xdescribe\(\|\.only\(' apps/backend/src/test apps/backend/src/integrationTest apps/web/src`: zero hits. The one test that was changed after a CI failure, `SeatHoldExpiryIT#expiry_doesNotBlockRegistration`, kept its "slowest registration < 2 s" assertion and its seat-count assertions; only the self-imposed cap on the loop changed | ☑ |
| U7 | `test-matrix.md` is updated for every row of this phase | M-28 … M-36 and M-76 carry real `Class#method` names; none is `chưa viết`; M-09 and M-78, touched by this phase, are updated | ☑ |
| U8 | `test-matrix.md` §4 and §5 are refreshed to this sign-off | Recounted from the Status column on 2026-10-28: 80 rows, `Not started` 40 · `Drafted` 1 · `Passing` 39; §4.1, §4.2 (17 → 15 `⚠` rows: M-31 and M-32 left), §4.5 (the M-09, M-28/M-32/M-34, M-33 and M-75 → M-76 deferrals removed) and §5 rewritten. **Recount if any row changes before signing** | ☑ |

---

## 5. Known Gaps and Deferred Items

No INV of this phase appears in this table.

| # | What is not done | Matrix row(s) | Why it is acceptable to defer | Picked up in |
| --- | --- | --- | --- | --- |
| 1 | The K6 adversarial script was run once, locally, as a smoke check, and is not in CI. Result: exactly 10 × `201` and 490 × `409`, 0 undecided, SQL invariant held. The documented p95 < 1000 ms was **crossed** (3.87 s) with 500 VUs, the application, Postgres, Redis and K6 on one laptop. No `503` occurred, so the retry-with-the-same-key loop was **not exercised**. The paid variant is not written | M-36 (`Drafted`) | `test-plan.md` §1: the script "is **not** the evidence gate for this phase". The latency number says nothing about the target topology | Phase 8 (M-71 … M-74, the same script at full topology) |
| 2 | "The nightly sync does not disrupt users being served" | M-11 | Not an invariant; a claim about latency under concurrent load, best measured under load (re-deferred 2026-10-26, checklist D29) | Phase 8 |
| 3 | The probes of `resilience-testing.md` §2.3 that need modules which do not exist yet: check-in sync and reporting unaffected by a Redis outage, and the rate limiter failing open | M-34 (limits of `Passing`) | Check-in is Phase 5 and the rate limiter is Phase 7 (INV-17); INV-08's own criterion, writes rejected and never uncoordinated, is met (§3 row 2) | Phase 5 (M-80), Phase 7 *(2026-11-01 note, not a rewrite of the sign-off: the Check-in half of this row is closed by Phase 5 checklist 5.5, `RedisOutageIT` now probes sync, the report and the attendance list during the outage; the rate-limiter half stays Phase 7)* |
| 4 | Workshop cancelled → its registrations cancelled and their seats released (`backend-coding-standards.md` §27 #8, the half still open) | — | Not scheduled in any phase; the hold-expiry consumer and the refund do not depend on it. Raised with the owner, not built silently | A decision of the owner |
| 5 | A hold-expiry message that arrives before `hold_expires_at` (clock skew beyond about 5 min 35 s) is parked after the retry chain, so INV-07's release is not automatic in that broken-environment case. Redis being away is covered (never parked) | M-33 (limit of `Passing`) | Decided by the letter of D19/D27: only "lock unavailable" is never parked. Making this exception retry unbounded is a one-line change (`RetryUntilAvailable` on `HoldNotYetExpiredException`) if the owner wants it | A decision of the owner |
| 6 | The Readiness `UP` half of AC21.2 (Redis unreachable at start-up: the application starts, registration answers `503`, Readiness stays `UP`) | M-34 (limit of `Passing`) | The `503` half is tested (`RedisRequiredStartupIT`); the Backend has no actuator or readiness endpoint yet | Phase 6 |
| 7 | The counters and gauges that make Phase 3 behaviour visible are registered but nothing exports them: `unihub_seat_hold_expiry_lock_unavailable_total`, `unihub_payment_refund_parked_total`, `unihub_payment_refund_required_oldest_age_seconds`, `unihub_seat_claim_version_conflicts_total`. With no actuator the global Micrometer registry has no backing registry, so they are no-ops in production today (tests attach a `SimpleMeterRegistry`). Their alert rules do not exist | — | Prometheus/Grafana do not exist before the observability stack; Phase 3 proves the behaviour the alerts watch | Phase 6 (alert rules, M-53/M-54) |
| 8 | Carried from Phase 2 §5: an automated browser check that no page violates the CSP (row 1); a payment the gateway keeps in `processing` > 24 h stays `PENDING` (row 4); reads fall back to the Master when the Replica is unreachable (row 5); the CSP as an HTTP header (row 7); audit publishing to RabbitMQ (row 8); alert rules on the parking lot and stuck payments (row 9); `correlationId` in problem bodies (row 10); first-login password setup for synced students (row 11) | M-78, M-23, M-22, M-17, M-54 | Unchanged from `phase-2.md` §5; none is touched by Phase 3. Rows 2, 3, 6 and 12 of that table were closed here (M-33, M-76, M-28/M-32/M-34, M-09/M-35) | As stated in `phase-2.md` §5 |

---

## 6. Confirmation

> I have read the exit criteria for Phase 3 in `test-plan.md` §1, ticked every box in §3 and §4 above against the evidence cited, and listed every deferral in §5 — none of which is a Non-Negotiable Invariant.
>
> Self-certified by: Nguyễn Thành Tiến  Date: 2026-10-28

*Drafted by Claude Code on 2026-10-28 from the evidence listed above; signed by Nguyễn Thành Tiến the same day.*
