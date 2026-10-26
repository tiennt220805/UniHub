# Phase 2 Sign-Off — Registration & Payment

Copy of `phase-template.md` §2–§6 for Phase 2. Bare filenames follow that template's citation convention (`test-plan.md`, `test-strategy.md` in `../../strategy/`; `test-matrix.md` in `../`).

**Status: signed — Phase 2 complete.** Every box in §3 and §4 is ticked, every deferral is in §5, and §6 was signed by Nguyễn Thành Tiến on 2026-10-26 (entered by Claude Code at his explicit instruction after he confirmed the content). Nothing in this file is evidence of a result that was not observed; each cell says what was run and where.

Prepared 2026-10-26 from `checklists/phase-2-registration-payment.md`.

---

## Conditions that stood in the way of signing — all settled

| # | What | State |
|---|---|---|
| B1 | **Exit criterion 5 needed the Check-in module.** "Never cascades into browsing/check-in" could not be met in Phase 2 because Check-in does not exist until Phase 5, and `phase-template.md` §5 forbids listing a non-`Passing` INV as a deferral. | **Settled 2026-10-26 by the owner: the criterion was amended** in `test-plan.md` §1 (Phase 2 deliverable and exit criterion, Phase 5 rows) and `test-strategy.md` §2, the way Phase 1 amended its criterion 3. Phase 2 proves browsing and free registration (M-24, M-27, `Passing`); the check-in and reporting half is the new Phase 5 row **M-80** (INV-05, SC-07), so SC-07 stays covered. |
| B2 | **The browser check of the Content-Security-Policy (D18).** | **Done 2026-10-26** in headless Google Chrome 155 against the production build: 0 violations over the whole journey (see M-78). It found and fixed a real one (Zod's `Function("")` probe reported as a `script-src` `eval` violation). |
| B3 | **CI must be green on the final commit.** | **Settled 2026-10-26:** the owner reported CI green on `b0bf898`. Later commits change documentation only. |

---

## 2. Header Fields

| Field | Value |
|---|---|
| Phase (1–8, `proposal.md` §15 / `test-plan.md` §1) | 2 |
| Phase name | Registration & Payment |
| Date | 2026-10-26 |
| Commit SHA the checklist was run against | `b0bf898` (CI stages 1–4, the `web` job and `secret-scan-gitleaks` green, as reported by Nguyễn Thành Tiến; later commits change documentation only). The test evidence below was produced on the working tree of 2026-10-26 that this commit contains |
| INV IDs in scope (the INV IDs column of this phase's row in `test-plan.md` §3) | INV-02, INV-03, INV-04, INV-05, INV-10 |
| Self-certified by | Nguyễn Thành Tiến |

---

## 3. Exit Criteria

Copied verbatim from the **Exit criteria** cell of `test-plan.md` §1, Phase 2 row — five semicolon-separated claims, five rows. Criterion 5 is the text as amended on 2026-10-26 (see below).

| # | Exit criterion (copy from `test-plan.md` §1, this phase's row) | Evidence | Met |
|---|---|---|---|
| 1 | Idempotency test proves a retried payment request produces zero duplicate charges | M-20, M-26 (and M-21) `Passing`: `ChargePaymentIdempotencyIT#sameKey_gatewayCalledOnce`, `#concurrentSameKey_inUse_gatewayCalledOnce`, `#timeoutAfterConfirmSent_pending_keyCompleted_retryReplays`, `PaymentControllerIT#success_is201_andReplaysIdentically`, `PaymentWebhookResolutionIT#redelivery_isNoOp`; the limits of M-26 are in §5 | ☑ |
| 2 | Master-routing test proves no stale-replica read can let a duplicate slip through | M-22 `Passing`: `MasterRoutingIT` (6 tests, WAL replay paused on a real streaming Replica, each with a control that proves the Replica is behind), `JdbcIdempotencyStoreIT#find_readsTheMaster_notTheStaleReplica`. Writing it found and fixed a real routing gap (`GetWorkshop` read the Replica for an organizer who had just written) | ☑ |
| 3 | outbox test proves the event row and the business write commit or roll back together — never one without the other | M-25 `Passing`: `RegistrationOutboxAtomicityIT#committed_hasBothRows`, `#failureAfterTheInsert_leavesNeither`, `#failingInsert_leavesNoRegistration`, `PaymentConfirmationIT#confirmFailsAfterTheOutboxInsert_leavesNeither`, `ChargePaymentIT#syncSuccess_tx2Rollback_leavesNeither` | ☑ |
| 4 | reconciliation test proves a stuck Pending payment resolves to Successful/Cancelled without ever receiving a webhook | M-23 `Passing`: `PaymentReconciliationIT#stuck_succeededAtTheGateway_isResolvedWithoutAWebhook`, `#raceWithTheWebhook_convergesOnOneStatusAndOneEvent`, `ReconciliationRequestedListenerIT#request_resolvesTheStuckPayment_withoutAWebhook`, `ReconcilePaymentTest` (one test per row of the D16 table) | ☑ |
| 5 | circuit-breaker test proves a payment-gateway outage never cascades into browsing or free registration (the check-in and reporting half is proven in Phase 5, once those modules exist: amended 2026-10-26, `../reports/test-matrix.md` §4.5, M-80) | M-24, M-27 `Passing`: `PaymentCircuitBreakerIT` (5 tests: a dead and a slow gateway open the one shared circuit, charges then fail fast with the gateway journal flat, recovery with no manual step, and browsing, free registration and the webhook keep answering with the circuit CLOSED, OPEN and HALF_OPEN), `ChargePaymentTimeBudgetIT` (3 tests with the real 3 s limits), `ResilientPaymentGatewayTest` (14), `BreakerGatewayAdmissionTest` | ☑ |

**Criterion 5 — amended.** The original text ("… never cascades into browsing/check-in") assumed a Check-in module. None exists before Phase 5, so the claim could only be proven for browsing and free registration. The owner chose, on 2026-10-26, to amend the criterion rather than leave INV-05 `Drafted` or keep Phase 2 open: the Phase 2 criterion and deliverable in `test-plan.md` §1 now name browsing and free registration, the Phase 5 row names the check-in and reporting probe, `test-strategy.md` §2 lists INV-05 under phases 2 and 5, and the matrix carries the rest as M-80 so that SC-07 ("browsing, check-in, reporting") is still covered by a row. The evidence above meets the amended text; M-80 is a Phase 5 row and is not a Phase 2 gap.

---

## 4. Universal Checklist

| # | Check | How verified | Done |
|---|---|---|---|
| U1 | Every INV in scope has Status `Passing` in `test-matrix.md` | INV-02: M-20, M-21, M-26 · INV-03: M-22 · INV-04: M-23, M-26 · INV-05: M-24, M-27 (as amended) · INV-10: M-25 — all `Passing` | ☑ |
| U2 | Every other matrix row of this phase is `Passing`, or is listed in §5 with a reason. No row is `Flaky — investigate` | M-08, M-18, M-19, M-75, M-77, M-78, M-79 `Passing`; M-76 is Phase 3 and M-80 Phase 5. None is `Flaky` | ☑ |
| U3 | Coverage ≥80% on the core business-logic services in this phase's scope | `jacocoTestCoverageVerification` green locally on 2026-10-26 (CI runs it in `unit-test`); 12 packages gated, lowest 94.2%: `payment.application.usecase` 94.2%, `payment.domain.model` 98.8%, `registration.application.usecase` 99.4%, `registration.domain.model` 100% (`test-matrix.md` M-15) | ☑ |
| U4 | Every CI stage that exists at this phase is green on the commit in §2 | `lint`, `unit-test`, `integration-test`, `contract-diff`, `secret-scan-gitleaks` and the `web` job green on `b0bf898`, as reported by the owner. `sast-semgrep` and `dependency-check` are non-blocking and not stages (`deployment-and-cicd.md` §5) | ☑ |
| U5 | No unresolved `TODO`/`FIXME` in this phase's test code | `grep -rnE 'TODO\|FIXME' apps/backend/src/test apps/backend/src/integrationTest apps/web/src apps/web/vite-plugins`: zero hits (2026-10-26) | ☑ |
| U6 | No test backing an INV of this phase is skipped or weakened | The same search for `@Disabled`, `.skip(`, `it.todo`, `xit(`, `xdescribe(`, `.only(`: zero hits. No test backing an INV of this phase was skipped or had an assertion relaxed | ☑ |
| U7 | `test-matrix.md` is updated for every row of this phase | Every `Class#method` cited anywhere in the matrix was machine-checked against the source on 2026-10-26 (wildcards and renamed tests fixed; M-77 filled in); no `chưa viết` on a `Passing` row | ☑ |
| U8 | `test-matrix.md` §4 and §5 are refreshed to this sign-off | Recounted from the Status column on 2026-10-26: 80 rows, `Not started` 50 · `Drafted` 0 · `Passing` 30; §4.1, §4.2, §4.3, §4.5 and §5 rewritten. **Recount if any row changes before signing** | ☑ |

---

## 5. Known Gaps and Deferred Items

No INV of this phase appears in this table. The check-in and reporting half of INV-05 is the Phase 5 row M-80 (see the amendment under §3), not a deferral.

| # | What is not done | Matrix row(s) | Why it is acceptable to defer | Picked up in |
|---|---|---|---|---|
| 1 | An automated, repeatable browser check that no page violates the CSP | M-78 (`Passing`) | The check was run once, in headless Chrome, with 0 violations (M-78); `csp-build.test.ts` asserts the built `index.html`. Nothing re-runs the browser check on every change: Playwright is not installed in `apps/web` | Phase 7 (Playwright suite, M-69), `test-matrix.md` §4.5 |
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

> I have read the exit criteria for Phase 2 in `test-plan.md` §1, ticked every box in §3 and §4 above against the evidence cited, and listed every deferral in §5 — none of which is a Non-Negotiable Invariant.
>
> Self-certified by: Nguyễn Thành Tiến  Date: 2026-10-26

*Entered by Claude Code on 2026-10-26 because Nguyễn Thành Tiến confirmed the content and explicitly asked for it to be signed on his behalf.*
