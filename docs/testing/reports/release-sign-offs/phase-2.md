# Phase 2 Sign-Off — Registration & Payment

Copy of `phase-template.md` §2–§6 for Phase 2. Bare filenames follow that template's citation convention (`test-plan.md`, `test-strategy.md` in `../../strategy/`; `test-matrix.md` in `../`).

**Status: DRAFT — not signed, and not signable yet.** §6 is deliberately blank: it is the owner's personal confirmation. Three things stand between this copy and a signature, listed under "Blocking the sign-off" below. Nothing in this file is evidence of a result that was not observed; each cell says what was run and where.

Prepared 2026-10-26 from `checklists/phase-2-registration-payment.md`. The commit SHA is left to be filled in at the final commit (§2).

---

## Blocking the sign-off

| # | What blocks it | Who resolves it |
|---|---|---|
| B1 | **Exit criterion 5 cannot be met as written.** M-24 and M-27 carry INV-05 and are `Drafted`, because "never cascades into browsing/**check-in**" needs the Check-in module, which does not exist until Phase 5 (reporting does not either). `phase-template.md` §5 forbids listing a non-`Passing` INV as a deferral: it would make the sign-off void. Browsing and free registration *are* proven (`PaymentCircuitBreakerIT#freeRegistration_browsing_andTheWebhook_areUnaffected`, circuit CLOSED, OPEN and HALF_OPEN). Two ways out, both the owner's: **(a)** reword the Phase 2 criterion in `test-plan.md` §1 (and §3's INV-05 row) so check-in is proven in Phase 5 — the Phase 1 sign-off amended criterion 3 the same way; **(b)** keep Phase 2 open until Check-in exists | Nguyễn Thành Tiến |
| B2 | **The browser check of the Content-Security-Policy (D18) is not done.** It needs a real browser; jsdom cannot enforce a CSP. M-78 stays `Drafted` until it is (it carries no INV, so it may be listed in §5 if the owner prefers to defer it) | Nguyễn Thành Tiến |
| B3 | **CI must be green on the final commit.** The project owner reported CI green on `daa7b6a` (2026-10-26, after the `web` job was added). Commits made since then change code and documentation, so §4 U4 is ticked only once CI is reported green on the last commit | Nguyễn Thành Tiến, after the final push |

---

## 2. Header Fields

| Field | Value |
|---|---|
| Phase (1–8, `proposal.md` §15 / `test-plan.md` §1) | 2 |
| Phase name | Registration & Payment |
| Date | *to be filled at signing* |
| Commit SHA the checklist was run against | *to be filled at the final commit* — the evidence below was produced on the working tree of 2026-10-26, after `daa7b6a` |
| INV IDs in scope (the INV IDs column of this phase's row in `test-plan.md` §3) | INV-02, INV-03, INV-04, INV-05, INV-10 |
| Self-certified by | *to be filled at signing* |

---

## 3. Exit Criteria

Copied verbatim from the **Exit criteria** cell of `test-plan.md` §1, Phase 2 row — five semicolon-separated claims, five rows.

| # | Exit criterion (copy from `test-plan.md` §1, this phase's row) | Evidence | Met |
|---|---|---|---|
| 1 | Idempotency test proves a retried payment request produces zero duplicate charges | M-20, M-26 (and M-21) `Passing`: `ChargePaymentIdempotencyIT#sameKey_gatewayCalledOnce`, `#concurrentSameKey_inUse_gatewayCalledOnce`, `#timeoutAfterConfirmSent_pending_keyCompleted_retryReplays`, `PaymentControllerIT#success_is201_andReplaysIdentically`, `PaymentWebhookResolutionIT#redelivery_isNoOp`; the limits of M-26 are in §5 | ☑ |
| 2 | Master-routing test proves no stale-replica read can let a duplicate slip through | M-22 `Passing`: `MasterRoutingIT` (6 tests, WAL replay paused on a real streaming Replica, each with a control that proves the Replica is behind), `JdbcIdempotencyStoreIT#find_readsTheMaster_notTheStaleReplica`. Writing it found and fixed a real routing gap (`GetWorkshop` read the Replica for an organizer who had just written) | ☑ |
| 3 | outbox test proves the event row and the business write commit or roll back together — never one without the other | M-25 `Passing`: `RegistrationOutboxAtomicityIT#committed_hasBothRows`, `#failureAfterTheInsert_leavesNeither`, `#failingInsert_leavesNoRegistration`, `PaymentConfirmationIT#confirmFailsAfterTheOutboxInsert_leavesNeither`, `ChargePaymentIT#syncSuccess_tx2Rollback_leavesNeither` | ☑ |
| 4 | reconciliation test proves a stuck Pending payment resolves to Successful/Cancelled without ever receiving a webhook | M-23 `Passing`: `PaymentReconciliationIT#stuck_succeededAtTheGateway_isResolvedWithoutAWebhook`, `#raceWithTheWebhook_convergesOnOneStatusAndOneEvent`, `ReconciliationRequestedListenerIT#request_resolvesTheStuckPayment_withoutAWebhook`, `ReconcilePaymentTest` (one test per row of the D16 table) | ☑ |
| 5 | circuit-breaker test proves a payment-gateway outage never cascades into browsing/check-in | M-24, M-27 are `Drafted`, not `Passing`. The browsing half is proven (`PaymentCircuitBreakerIT`, 5 tests; `ChargePaymentTimeBudgetIT`, 3 tests with the real 3 s limits); the **check-in half cannot be, until Phase 5** — see B1 | ☐ |

---

## 4. Universal Checklist

| # | Check | How verified | Done |
|---|---|---|---|
| U1 | Every INV in scope has Status `Passing` in `test-matrix.md` | INV-02: M-20, M-21, M-26 · INV-03: M-22 · INV-04: M-23, M-26 · INV-10: M-25 — all `Passing`. **INV-05: M-24, M-27 are `Drafted`** (B1) | ☐ |
| U2 | Every other matrix row of this phase is `Passing`, or is listed in §5 with a reason. No row is `Flaky — investigate` | M-08, M-18, M-19, M-75, M-77, M-79 `Passing`; M-78 `Drafted` and listed in §5; M-76 is Phase 3. None is `Flaky` | ☑ |
| U3 | Coverage ≥80% on the core business-logic services in this phase's scope | `jacocoTestCoverageVerification` green locally on 2026-10-26 (CI runs it in `unit-test`); 12 packages gated, lowest 94.2%: `payment.application.usecase` 94.2%, `payment.domain.model` 98.8%, `registration.application.usecase` 99.4%, `registration.domain.model` 100% (`test-matrix.md` M-15) | ☑ |
| U4 | Every CI stage that exists at this phase is green on the commit in §2 | The owner reported `lint`, `unit-test`, `integration-test`, `contract-diff`, `secret-scan-gitleaks` and the new `web` job green on `daa7b6a`. **Pending on the final commit** (B3) | ☐ |
| U5 | No unresolved `TODO`/`FIXME` in this phase's test code | `grep -rnE 'TODO\|FIXME' apps/backend/src/test apps/backend/src/integrationTest apps/web/src apps/web/vite-plugins`: zero hits (2026-10-26) | ☑ |
| U6 | No test backing an INV of this phase is skipped or weakened | The same search for `@Disabled`, `.skip(`, `it.todo`, `xit(`, `xdescribe(`, `.only(`: zero hits. No test backing an INV of this phase was skipped or had an assertion relaxed | ☑ |
| U7 | `test-matrix.md` is updated for every row of this phase | Every `Class#method` cited anywhere in the matrix was machine-checked against the source on 2026-10-26 (wildcards and renamed tests fixed; M-77 filled in); no `chưa viết` on a `Passing` row | ☑ |
| U8 | `test-matrix.md` §4 and §5 are refreshed to this sign-off | Recounted from the Status column on 2026-10-26: 79 rows, `Not started` 49 · `Drafted` 3 · `Passing` 27; §4.1, §4.2, §4.3, §4.5 and §5 rewritten. **Recount if any row changes before signing** (for example M-78 after D18, M-24/M-27 after B1) | ☑ |

---

## 5. Known Gaps and Deferred Items

No INV appears in this table. The check-in half of INV-05 is a blocker (B1), not a deferral.

| # | What is not done | Matrix row(s) | Why it is acceptable to defer | Picked up in |
|---|---|---|---|---|
| 1 | The console check that no page violates the CSP on `vite preview` (D18) | M-78 (`Drafted`, no INV) | jsdom cannot enforce a CSP and Playwright is not installed; the built `index.html` is asserted instead (the nine directives, no inline script or style). Unless the owner does it before signing | Phase 7 (Playwright), `test-matrix.md` §4.5 |
| 2 | Seat-hold expiry: a `RESERVED` seat of a paid registration never expires on its own | M-33 (Phase 3) | The `hold.reserved` job is written to the outbox in the same transaction as the hold; the consumer is Phase 3's (INV-07). Polling for expired holds is excluded by `architecture.md` §4.3, so no cron was added | Phase 3 |
| 3 | The gateway refund (`REFUND_REQUIRED → REFUNDED`) | M-76 (Phase 3) | Nothing on the live path can reach the compensation yet (no hold-expiry consumer, no cancel endpoint); Phase 2 stops at `REFUND_REQUIRED`. Until then a payment that succeeded after its hold ended holds the student's money at that status | Phase 3 |
| 4 | A payment the gateway keeps in `processing` for more than 24 h stays `PENDING` | M-23 (limit of `Passing`) | Reconciliation stops acting, logs `WARN` and counts `unihub_payment_reconciliation_stuck_total`; the alert on it needs the metrics stack | Phase 6 (alert rules) |
| 5 | With the Replica unreachable (not lagging), reads fall back to the Master | M-22 (limit of `Passing`) | Direction approved, but there is no health check to drive the routing; `resilience-testing.md` §2.5 keeps it as a TODO | When the routing health check is designed |
| 6 | The seat lock does not coordinate more than one instance, and a Redis outage does not refuse registration | M-28, M-32, M-34 (Phase 3) | `InProcessSeatLock` (like `InProcessJobLock`); the deciding layer is the database check (`workshops.version` + `seats_taken <= capacity`), which holds across instances | Phase 3 |
| 7 | The CSP as an HTTP header at the serving layer (`frame-ancestors` cannot be set from a `<meta>` tag) | — | Phase 2 ships it as a `<meta>` tag in the production build; there is no serving layer of its own before Kubernetes | Phase 7 |
| 8 | Audit publishing to RabbitMQ and the consumer that persists `audit_events` | M-54 (Phase 6) | Phase 2 audits through the `AuditTrail` port into structured log lines; publishing without a consumer would grow a queue without bound. Every charge, payment transition and registration confirmation is already audited through the port | Phase 6 |
| 9 | Alert rules on `unihub.parking-lot` depth and on stuck payments | — | Prometheus/Grafana do not exist yet; Phase 2 proves the behaviour the alerts watch | Phase 6 |
| 10 | `correlationId` in problem bodies | M-17 (limit of `Passing`) | No correlation-ID filter exists yet; the property is optional in `error.yaml` | Phase 6 (M-53) |
| 11 | First-login password setup for a student synced from the legacy system | — (D9) | A student with no password fails like a wrong password; Phase 2 seeds dev/test students only. No document designs the mechanism | A decision of the owner |
| 12 | The cross-instance job locks (nightly sync, idempotency cleanup, reconciliation sweep) | M-09, M-35 (Phase 3) | One instance in Phase 2: the jobs go through the `JobLock` port, backed by `InProcessJobLock`; `RedissonJobLock` is Phase 3 (INV-09) | Phase 3 |

---

## 6. Confirmation

*Left blank on purpose. This is the owner's own statement, to be written when B1, B2 and B3 are settled and every box in §3 and §4 is ticked:*

> I have read the exit criteria for Phase 2 in `test-plan.md` §1, ticked every box in §3 and §4 above against the evidence cited, and listed every deferral in §5 — none of which is a Non-Negotiable Invariant.
>
> Self-certified by: ___  Date: ___
