# Test Strategy — UniHub

This file is the physical-level completion of `proposal.md` §13 (Testing & QA Strategy) and `requirement.md` §3.2 (Quality & Performance Testing Requirements), for the parts of that macro-level strategy that need a concrete ratio, gate, or environment decision before any test gets written. Nothing here overrides those two documents — where this file and they appear to disagree, `proposal.md`/`requirement.md` are the authority and this file has a bug.

**Out of scope, and where it actually lives:** which tool/assertion style belongs to which test level (`../test-levels/*.md`); when to mock vs. require real infrastructure, and which functional test-design technique to apply to which kind of logic (`../guidelines/*.md`); concrete load/adversarial-concurrency scenario parameters (`../non-functional/performance-load.md`); resilience/fault-injection scenario mechanics — circuit breaker, Redis outage, replica lag, pod kill (`../non-functional/resilience-testing.md`); adversarial security-test mechanics — JWT tampering, QR forgery, rate-limit bypass (`../non-functional/security-testing.md`); the requirement → test-case traceability record (`../reports/test-matrix.md`); the phase-by-phase schedule of what gets tested when (`test-plan.md`, this same folder).

---

## Table of Contents

- [1. Objectives](#1-objectives)
- [2. Non-Negotiable Invariant Catalog](#2-non-negotiable-invariant-catalog)
- [3. Test Pyramid & Sizing](#3-test-pyramid-sizing)
- [4. Quality Gates](#4-quality-gates)
- [5. Environment Strategy](#5-environment-strategy)
- [6. Traceability & Reporting](#6-traceability-reporting)
- [7. Reference Table](#7-reference-table)

---

## 1. Objectives

**Rationale:** `proposal.md` §12's success-criteria table is the actual target — this whole `docs/testing/` tree exists to produce evidence for those rows, not to satisfy testing for its own sake. Two of those rows (Overbooking, Concurrency Correctness) map directly to invariants `CLAUDE.md` marks Non-Negotiable; a test strategy that waters either down to make a deadline is fixing the wrong thing.

**Model:** every test this project writes serves one of these objectives, condensed from `proposal.md` §12 (wording tightened for a table cell; the Success Criterion column is a paraphrase, not a quotation — see `proposal.md` §12 itself for the exact source sentence):

| Objective | Success Criterion (condensed from `proposal.md` §12) |
|---|---|
| Prove no overbooking | 0% — zero seats sold or held beyond configured capacity, across all load-test scenarios |
| Prove peak load capacity | System remains available and correct serving ~12,000 concurrent users within a 10-minute window, 60% of that traffic in the first 3 minutes |
| Prove registration latency | Sub-second (p95) response time for seat-reservation and registration-confirmation endpoints under peak load |
| Prove concurrency correctness | Under contention for a small number of remaining seats, exactly that number succeed; all others get a clear, immediate rejection |
| Prove payment consistency | Zero duplicate charges under retried/duplicated requests; zero payments left permanently unresolved |
| Prove check-in data integrity | Exactly one valid check-in per ticket after offline-sync reconciliation, zero data loss |
| Prove fault isolation | Payment gateway outages cause zero cascading failure into browsing/check-in/reporting |
| Prove API contract consistency | All API responses conform to their defined shape across success, conflict, and failure paths |
| Prove sufficient coverage | ≥80% code coverage on core business-logic services |

**Guarantees:** these nine rows are non-negotiable acceptance criteria for the test suite as a whole (per `CLAUDE.md`'s framing of `proposal.md`'s Guarantees). A change that makes a test pass by weakening the assertion against one of these rows is not a passing test — it is scope creep into `proposal.md` §12 and must be flagged, not merged.

---

## 2. Non-Negotiable Invariant Catalog

**Rationale:** `test-plan.md` needs to point at a stable identifier when it says "this deliverable proves an invariant," not a prose paraphrase that has to be re-matched by hand every time either file changes. `CLAUDE.md`'s Non-Negotiable Invariants list is the primary source, but it is a curated subset — several correctness-critical properties are stated just as firmly in `requirement.md` and `architecture.md` without being promoted into that specific list. Both kinds get an ID here, but only the ones `CLAUDE.md` actually names are cited to `CLAUDE.md`; the rest cite the SRS/architecture section directly, so nobody mistakes a requirement-level guarantee for a `CLAUDE.md` one or vice versa.

**Model:**

| ID | Invariant | Source | Level | Testcontainers? | Phase (`test-plan.md`) |
|---|---|---|---|---|---|
| INV-01 | RBAC is permission-based, never role-name-based; the JWT carries the resolved permission list | `CLAUDE.md`; `requirement.md` §2.11; `architecture.md` §5.1 | Integration | Y | 1 |
| INV-02 | The idempotency record is written in the same PostgreSQL transaction as the business effect it guards; a unique constraint is the enforced backstop | `CLAUDE.md`; `requirement.md` §2.8; `architecture.md` §4.5 | Integration | Y | 2 |
| INV-03 | Consistency-sensitive reads (idempotency-key lookups, immediate post-write views) are routed to the Master, never the Replica | `architecture.md` §4.1 — not named in `CLAUDE.md` | Integration | Y | 2 |
| INV-04 | A payment stuck "Pending" >15 min is reconciled by a scheduled job that queries the gateway's status API and resolves the ticket; never left outstanding | `requirement.md` §1.5, §2.10; `proposal.md` §16 | Integration | Y | 2 |
| INV-05 | A payment gateway outage opens the circuit breaker; browsing, check-in, and reporting keep working (fault isolation) | `requirement.md` §2.7; `proposal.md` §11.3, §12 | Integration | Y | 2 (browsing, free registration), 5 (check-in, reporting) |
| INV-06 | Anti-overbooking is layered — advisory distributed lock + optimistic-lock version check + DB check constraint — never a single mechanism | `CLAUDE.md`; `requirement.md` §2.6; `architecture.md` §4.2; `proposal.md` §11.1 | Integration | Y | 3 |
| INV-07 | A seat left "Reserved" >15 min is released automatically and asynchronously, without blocking the registration request path | `requirement.md` §1.5, §2.10; `architecture.md` §4.3 | Integration | Y | 3 |
| INV-08 | If Redis coordination is unavailable, registration writes are rejected (fail-safe) — never allowed to proceed uncoordinated | `architecture.md` §4.2 (citing its own §1.4 fail-safe boundary) — not named in `CLAUDE.md` | Integration | Y | 3 |
| INV-09 | A scheduled background job (seat-hold release, payment reconciliation, nightly sync) executes on exactly one instance at a time | `requirement.md` §2.10; `architecture.md` §4.6; `proposal.md` §11.5 | Integration | Y | 3 |
| INV-10 | Event publication uses the transactional outbox pattern: business write and intent-to-publish commit atomically, before the broker ever sees the message | `CLAUDE.md`; `architecture.md` §4.3, §6.1 | Integration | Y | 2 (write-side atomicity), 4 (relay/consume proof) |
| INV-11 | Every consumer is idempotent under redelivery — RabbitMQ is at-least-once, reprocessing the same message is a safe no-op | `CLAUDE.md`; `architecture.md` §4.3 | Integration | Y | 4 |
| INV-12 | A check-in ticket is self-verifying offline via signature; duplicate scans reconcile earliest-timestamp-wins, exactly one valid check-in per ticket | `CLAUDE.md`; `requirement.md` §1.5, §2.9; `architecture.md` §5.3 | Unit (on-device signature) + Integration (server reconciliation) | N (on-device) / Y (server) | 5 |
| INV-13 | The nightly student-sync pipeline isolates malformed/duplicate records into an error store; a handful of bad records never halts the batch | `requirement.md` §1.5, §2.10 | Integration | Y | 1 (assumption — `proposal.md` §15 doesn't name a phase for nightly sync; Phase 1 is chosen because the User module, which owns the sync, is built there) |
| INV-14 | An audit-pipeline outage never blocks or fails the business operation it would have recorded; the publish failure itself surfaces as an operational alert | `CLAUDE.md`; `architecture.md` §5.5 | Integration | Y | 6 |
| INV-15 | Schema migrations are explicit/versioned and tolerate being read by both the old and new pod version mid-rollout | `CLAUDE.md`; `architecture.md` §6.2 | Integration/deployment | Y | 7 |
| INV-16 | An unhealthy pod is restarted automatically; traffic is only routed to ready instances | `requirement.md` §3.3; `proposal.md` §14 | Deployment smoke (full topology) | N | 7 |
| INV-17 | Rate limiting is enforced off cluster-wide shared state, not per-instance | `requirement.md` §2.5; `architecture.md` §4.2 | Integration (≥2 instances) | Y | 7 |
| INV-18 | Secrets are never committed to source or config; injected at runtime in every environment | `CLAUDE.md` | Static/CI check (Gitleaks secret-scanning — a blocking gate, `deployment-and-cicd.md` §5's Security Scan Jobs ADR; code review) — not a runtime test | N | N/A — enforced continuously, all phases |
| INV-19 | An Object Storage (AWS S3) outage is isolated to the Workshop module's brochure upload/summarization flow; it never cascades into Registration, Payment, or Check-in | `architecture.md` §4.4; `workshop.md` §10 — not named in `CLAUDE.md` | Integration | Y (MinIO) | 5 (assumption — `proposal.md` §15 names no phase for it; Phase 5 is chosen because it is the first phase in which all three modules the guarantee protects — Registration, Payment, Check-in — exist) |
| INV-20 | Every connection, external and internal, is mutually authenticated; a plaintext connection to any pod in the mesh is refused (mesh mTLS mode STRICT). **Pending — not in force:** the mesh runs PERMISSIVE until the Mesh mTLS Mode ADR's three preconditions are met (`test-plan.md` §1, Phase 7 exit criteria); the ID is reserved, and no test may claim to prove this until STRICT is enabled | `architecture.md` §5.4; `deployment-and-cicd.md` §2 (Mesh mTLS Mode ADR) — not in `CLAUDE.md`'s invariants list | Deployment smoke (full topology) | N | 7 |

**Guarantees:** INV-19 and INV-20 were appended in a later review; INV-01…INV-18 keep their IDs. Every row has a Phase; `test-plan.md` §1 and §2 reference these rows by ID instead of restating "a Non-Negotiable Invariant" in prose. A phase's exit criteria is not met until the INV rows scheduled at that phase are proven at the Level and Testcontainers requirement stated here — a Unit test with a mocked datastore does not satisfy a row marked Testcontainers: Y.

---

## 3. Test Pyramid & Sizing

**Rationale:** the generic pyramid (mostly small/unit, some medium/integration, few large/e2e) assumes correctness lives mostly in pure logic. This project's riskiest correctness properties — seat-lock/optimistic-lock interplay, transaction isolation, at-least-once redelivery — are exactly the properties `proposal.md` §13 says an in-memory substitute would not reproduce faithfully. So this project's pyramid is deliberately fatter in the middle than the textbook default; that is a considered deviation, not an accident to "fix" toward 80/15/5 later.

**Model:**

| Level | Resource size | Tooling | Target share of suite | Maps to |
|---|---|---|---|---|
| Unit | Small — single process, no I/O | JUnit 5 + Mockito; plus `@WebMvcTest` web slices (`*WebTest`) — still single process, no I/O | ~65% | `../test-levels/unit-testing.md` (web slices: §4.1) |
| Integration | Medium — localhost, real Postgres/Redis/RabbitMQ via Testcontainers | JUnit 5 + Testcontainers | ~25% | `../test-levels/integration-testing.md` |
| Contract | Medium — schema diff, no runtime infra | springdoc + OpenAPI diff vs. `packages/api-contract` | not measured as a test-case share (see note below) | `../test-levels/contract-testing.md` |
| E2E | Large — full stack, browser | Playwright | ~5% | `../test-levels/e2e-testing.md` |
| Load / adversarial-concurrency | Large — deployed instance, external driver | K6 / JMeter | not part of the per-push suite (§4) | `../non-functional/performance-load.md` |

The elevated Integration share (~25% vs. a typical ~15%) is intentional: it is the tier carrying the anti-overbooking, idempotency, and outbox invariants that a mock cannot verify.

**Note on the ratio:** "Target share of suite" is a planning guideline, not a gate — it's counted by test-case count (number of `@Test`/scenario methods) across Unit/Integration/E2E. Contract-level checks aren't test cases in that sense — they're per-endpoint schema-diff assertions generated from `packages/api-contract`, so folding a "~5%" figure into the same test-case ratio would be comparing two different units. Contract coverage is tracked separately, as "percentage of contract endpoints diffed," not as a slice of the 65/25/5/5 split. The Unit ~65% / Integration ~25% / E2E ~5% figures themselves are unchanged by this note.

**Guarantees:** a business-logic path touching one of `CLAUDE.md`'s Non-Negotiable Invariants must have an Integration-level (Testcontainers) test proving it against real Postgres/Redis/RabbitMQ — a passing Unit test with a mocked datastore never substitutes for this, and reviewers should treat "the unit tests pass" as insufficient evidence for these specific paths.

---

## 4. Quality Gates

**Rationale:** `requirement.md` §3.2's CI pipeline (Lint → Unit → Integration(Testcontainers) → Docker package/push) is the actual mechanism that turns "should be tested" into "cannot merge otherwise." This section states which of `docs/testing/`'s levels are wired into that blocking pipeline and which are not, so nobody assumes E2E or load testing gates a merge when they don't. Stages 1–4 are deliberately stood up in Phase 1 rather than waiting for `proposal.md` §15 Phase 7 (which bundles CI/CD with the Kubernetes deployment work) — otherwise "every push/PR is gated" would be false for six of the eight phases. Only Docker packaging (stage 5) genuinely needs Phase 7's containerization/K8s work to exist; the test-gate part of CI doesn't. This is a deliberate reading of `test-plan.md`'s schedule, not a change to `proposal.md`'s phase scope — flagged here explicitly as an intentional deviation, not a silent one.

**Model:**

| Stage | Runs on | Blocking? | Coverage/pass requirement |
|---|---|---|---|
| 1. Lint/format | Every push/PR to `main` | Yes | Zero violations |
| 2. Unit tests | Every push/PR to `main` | Yes | 100% pass; ≥80% coverage on core business-logic services (not the whole repo). "Core business-logic service" means the service-layer and domain logic narrated in each module's `technical-design/modules/*.md` story — e.g. the seat-reservation/seat-hold logic in `registration.md`, payment orchestration and reconciliation in `payment.md`, permission evaluation in `user.md`, offline-scan reconciliation in `checkin.md` — not a specific class name, since no source code exists yet (`CLAUDE.md`). Excluded from the denominator: DTOs/request-response models, configuration classes, the generated `packages/api-contract` TS/Java client, and mappers |
| 3. Integration tests (Testcontainers) | Every push/PR to `main` | Yes | 100% pass |
| 4. Contract diff (OpenAPI vs. `packages/api-contract`) | Every push/PR to `main`, from Phase 1 onward — wired in as soon as the first real backend OpenAPI output exists (User/Workshop endpoints), not deferred to Phase 7 (`test-plan.md` §1) | Yes | Zero unreviewed drift |
| 5. Docker package/push | Every push/PR to `main`, from Phase 7 onward (the K8s/CI-CD phase — `test-plan.md` §1) | Yes (final step, once reached) | Steps 1–4 must pass first |
| Secret scan (Gitleaks) | Every push/PR to `main`, in parallel with stages 1–4; `build-and-push` (stage 5) `needs:` it (`deployment-and-cicd.md` §5's Security Scan Jobs ADR) | Yes — a required status check on `main` once the manual branch-protection setup (`deployment-and-cicd.md` §5) is done | Zero findings; justified false positives go in the committed allowlist |
| Security scans (Semgrep SAST, OWASP Dependency-Check) | Every push/PR to `main`, in parallel with — not inside — stages 1–5 | No — non-blocking until each meets that ADR's promotion conditions | Findings reported and visible; not enforced |
| E2E (Playwright) | Pre-release only, tied to `test-plan.md` §1 Phase 7's Minikube smoke-test deliverable (`../test-levels/e2e-testing.md` §3) | No — evidence gate for Phase 7+ sign-off, not a per-push CI gate | Full Playwright suite passes against a fresh Minikube deploy |
| Load / adversarial-concurrency (K6/JMeter) | Dedicated sessions only (§5), scheduled per `test-plan.md` (first local, smoke-only draft in Phase 3 — not an evidence gate; full-topology evidence run in Phase 8) | No — evidence gate for a phase, not a per-push CI gate | 0% overbooking; full success-criteria table (§12) at Phase 8 |

**Guarantees:** steps 1–5 and the Gitleaks secret scan are the only gates a routine commit must clear; the Gitleaks secret scan is the one added gate (INV-18); the Semgrep and Dependency-Check scans run on every push but gate nothing until promoted. Load testing intentionally never runs on every push — `proposal.md` §16's resource-consumption risk row already decided this (full topology reserved for dedicated sessions) — so its absence from the per-push pipeline is a documented trade-off, not a gap.

---

## 5. Environment Strategy

**Rationale:** `proposal.md` §16 flags running the full Kubernetes/Istio/replica/messaging topology on every iteration as a development-instability risk, and mitigates it by constraining day-to-day resource use and reserving the full topology for dedicated load-test sessions. This section makes that mitigation concrete as three environment tiers instead of one.

**Model:**

| Tier | Scope | When used |
|---|---|---|
| Local dev | Ephemeral Testcontainers (single Postgres/Redis/RabbitMQ container each), no Kubernetes, no Istio | Every day, every `./gradlew test` run |
| CI (GitHub Actions) | Same ephemeral Testcontainers, spun per pipeline run | Every push/PR (§4, stages 1–4) |
| Full topology (Minikube) | Full K8s manifests, replicas, Istio sidecars, RabbitMQ, Prometheus/Grafana | Dedicated load-test sessions only, per the schedule owned by `test-plan.md` (as of this writing: the Phase 8 full-topology runs — Phase 3 stays on local/Testcontainers, see `test-plan.md` §1) — never a routine dev/CI dependency |

**Guarantees:** correctness (unit + integration + contract) is provable without ever standing up the full topology. Only throughput/latency/overbooking-under-real-load claims require it, and those are scheduled events (`test-plan.md`), not a standing requirement for every commit.

---

## 6. Traceability & Reporting

**Rationale:** a coverage number or a green CI badge proves tests ran, not that the right tests exist for every requirement. `../reports/test-matrix.md` is the living record that ties each `requirement.md` line item to the technique used to derive its test cases (`../guidelines/test-design-techniques.md`) and the concrete test(s) that exercise it — it must be updated as test cases are written, not just read as a static blueprint.

**Model:** `../reports/` holds only hand-maintained planning/traceability documents (the test matrix, phase sign-off templates) — never committed CI-generated output. Coverage reports and load-test result artifacts are CI/build artifacts (uploaded by the pipeline, e.g. as a GitHub Actions artifact or a dashboard), consistent with how `packages/api-contract` was kept out of `docs/` for being tooling-consumed rather than prose: generated output does not belong in the design corpus either.

**Guarantees:** every subsection of `proposal.md` §11 (11.1 overbooking, 11.2 payment idempotency, 11.3 gateway failures, 11.4 offline check-in, 11.5 scheduled background work) and every INV row in §2 has at least one traceable row in `../reports/test-matrix.md` before that module is considered test-complete for a phase (see `test-plan.md`'s per-phase exit criteria). Outbox (INV-10) is not a `proposal.md` §11 scenario — it traces to `architecture.md` §4.3, §6.1 (and `CLAUDE.md`), so its matrix rows carry an `ARC-INV-10` ID rather than a `P11.n` one.

---

## 7. Reference Table

| Item | Value | Source |
|---|---|---|
| Coverage gate | ≥80%, core business-logic services only | `requirement.md` §3.2 |
| CI pipeline order | Lint → Unit → Integration(Testcontainers) → Contract diff → Docker package/push | `requirement.md` §3.2 (steps 1–3, 4 extended with contract diff) |
| Pyramid target ratio | Unit ~65% / Integration ~25% / E2E ~5%, measured by test-case count; Contract tracked separately as endpoint-diff coverage, not part of this ratio (see §3's note — Unit/Integration/E2E figures unchanged from the original split; the resulting ~5% gap against 100% is left open rather than silently rebalanced) | §3, this file |
| Load-test scenarios | 12,000 CCU / 10-min surge; 500 VU vs. last 10 seats | `proposal.md` §12, `requirement.md` §3.2 |
| Environment tiers | Local dev (Testcontainers) / CI (Testcontainers) / Full topology (Minikube, dedicated sessions only) | §5, this file |
| Traceability record | `../reports/test-matrix.md` | §6, this file |
| Non-Negotiable Invariant catalog | INV-01…INV-20 | §2, this file |
