# Test Matrix — UniHub Test Reports

This file is the physical-level completion of `../strategy/test-strategy.md` §6 (Traceability & Reporting), for the question that section states as a Guarantee but cannot answer by itself: *for a given requirement, is there a test, where does it live, at which level, and which technique produced its cases?* It is the requirements traceability matrix (RTM) that `../guidelines/test-design-techniques.md` §1 (Guarantees) and `../guidelines/test-data-management.md` §4 both point at. Nothing here overrides `test-strategy.md`, `test-plan.md`, or `test-design-techniques.md` — where this file and they appear to disagree, those are the authority and this file has a bug.

**Living template — blank and pending cells are by design.** This file and `release-sign-offs/phase-template.md` are the only two files in `docs/testing/` allowed to carry unfilled or pending content, because they record *progress*, not decisions; every other file in this tree records a decision that is already made. An empty **Test case(s)** cell (`chưa viết`) next to `Not started` is the correct state of a row whose test does not exist yet — it is not an omission to be "completed" by inventing a method name. **No source code exists yet** (`CLAUDE.md`, Project Status), so no test in this matrix has been written or run: every row in §2.2 is `Not started`, and the only rows that show any other status are the clearly-labelled illustrations in §2.3, which are not reports.

**Out of scope, and where it actually lives:** what each technique is and where its concrete domain facts come from (`../guidelines/test-design-techniques.md`); which level and tooling a test belongs to (`../test-levels/*.md`, `../non-functional/*.md`); how a test is named, structured and isolated (`../guidelines/coding-standards.md`, `../guidelines/test-data-management.md`); what must be true before a phase is called complete (`../strategy/test-plan.md` §1 — and the checklist that records it, `release-sign-offs/phase-template.md`); coverage percentages and load-test result artifacts (CI/build artifacts, never committed — `../strategy/test-strategy.md` §6).

**Citation convention:** bare filenames are resolved by folder — `coding-standards.md`, `mocking-guidelines.md`, `test-data-management.md`, `test-design-techniques.md` live in `../guidelines/`; `unit-testing.md`, `integration-testing.md`, `contract-testing.md`, `e2e-testing.md` in `../test-levels/`; `performance-load.md`, `resilience-testing.md`, `security-testing.md` in `../non-functional/`; `test-strategy.md`, `test-plan.md` in `../strategy/`. `requirement.md`, `proposal.md` and `architecture.md` are at `../../` (the `docs/` root); `CLAUDE.md` is at the repository root.

---

## Table of Contents

- [1. Objectives](#1-objectives)
- [2. Traceability Matrix](#2-traceability-matrix)
  - [2.1 Conventions](#21-conventions)
  - [2.2 The Matrix](#22-the-matrix)
  - [2.3 Worked Example of Filled Rows (Illustrative Only)](#23-worked-example-of-filled-rows-illustrative-only)
- [3. Update Discipline](#3-update-discipline)
- [4. Coverage Gaps](#4-coverage-gaps)
- [5. Reference Table](#5-reference-table)

---

## 1. Objectives

**Rationale:** a green CI badge or a coverage percentage proves that tests ran, not that the right tests exist for every requirement (`test-strategy.md` §6). The matrix is the one place where "does requirement X have a test yet, at which level, derived by which technique" has a written answer instead of being reconstructed from memory. It is deliberately **not** a coverage report — coverage is a CI artifact and does not belong in `docs/`.

**Model:**

| Question the matrix answers | Where |
|---|---|
| Does requirement X (from `requirement.md`, `proposal.md` §11/§12, or an invariant in `test-strategy.md` §2) have a test? | §2.2, columns **Req ID** and **Status** |
| Which technique produced its cases, and where are the domain facts behind them? | §2.2, column **Technique** (links into `test-design-techniques.md`) |
| At which level and in which phase does the test live? | §2.2, columns **Level** and **Phase** |
| What is still missing, in one view? | §4 |
| How far along is the whole suite? | §5 (snapshot) |

**Who reads it:** the reviewer who certifies a phase "test-complete" — `test-strategy.md` §6's Guarantee says every `proposal.md` §11 scenario and every INV row has at least one traceable row here before then — which in this single-developer project is the author wearing the reviewer's hat at sign-off (`release-sign-offs/phase-template.md`). It is also the first place to look when asking "what would I have to write next".

**Guarantees:** every INV in `test-strategy.md` §2 (INV-01…INV-20), every row of `proposal.md` §12's success-criteria table (nine rows), every subsection of `proposal.md` §11 (11.1–11.5), and every requirement ID derived from `requirement.md` (§2.1) appears in at least one row of §2.2. A requirement with no planned test still gets a row — marked `⚠` — so a gap is visible rather than absent. No row claims a test exists that does not.

---

## 2. Traceability Matrix

### 2.1 Conventions

**Rationale:** `requirement.md` carries section numbers (`1.3.1`, `2.6`, `3.2`) but no ID per requirement bullet, and `test-strategy.md` §2 has invariant IDs but no ID for the requirement behind each. A stable scheme is needed before any row can say which requirement it traces to.

**Model — identifier scheme** (Req ID column):

| Prefix | Refers to | Numbering |
|---|---|---|
| `REQ-<section>.<n>` | A bullet of `requirement.md` | `<section>` is the SRS section number as written (e.g. `2.6`, `1.3.1`); `<n>` is the 1-based position of the **top-level bullet** within that section, in document order. A nested bullet takes a letter suffix. Examples: `REQ-2.6.4` = fourth bullet of §2.6 (the optimistic-locking safeguard); `REQ-1.3.1.3b` = the "Paid tickets" sub-bullet of §1.3.1's third bullet; `REQ-3.3.1c` = the "ConfigMaps & Secrets" sub-bullet of §3.3's first bullet; `REQ-3.2.3a` / `REQ-3.2.3b` = the 12,000-user and 500-VU sub-bullets of §3.2's third bullet. `REQ-3.3.2` is the CI bullet as a whole — its four numbered steps are not split. §1.1 and §1.2 (background, user groups) yield no IDs: their normative content is restated as bullets in §1.3, §1.5 and §2.11. |
| `SC-nn` | Row *n* of `proposal.md` §12's success-criteria table, in table order | `SC-01` Overbooking · `SC-02` Peak Load Capacity · `SC-03` Registration Latency · `SC-04` Concurrency Correctness · `SC-05` Payment Consistency · `SC-06` Check-in Data Integrity · `SC-07` Fault Isolation · `SC-08` API Contract Consistency · `SC-09` Test Coverage (the same nine `test-strategy.md` §1 lists) |
| `P11.n` | `proposal.md` §11.n | `P11.1` overbooking · `P11.2` payment idempotency · `P11.3` gateway failures · `P11.4` offline check-in · `P11.5` scheduled background work |
| `ARC-INV-nn` | Invariant INV-nn, used **only** when that invariant has no bullet of `requirement.md` or `proposal.md` §11/§12 stating it | The row's Requirement cell names the real source (`architecture.md` §, `CLAUDE.md`). A row whose invariant is stated in the SRS uses the `REQ-` ID instead. Every `ARC-INV` row is listed in §4.3 |

A row may carry several Req IDs when one set of tests proves them all (many-to-many is legal), and a Req ID may appear in several rows when its proof spans levels or phases. What is not legal is a Req ID that appears in no row.

**Model — columns:**

| Column | Contents | Filled from |
|---|---|---|
| **Row** | Stable key `M-nn`. Never renumbered, never reused; a new row takes the next free number wherever it sits in the phase order | This file |
| **Req ID** | Identifier(s) per the scheme above | `requirement.md`, `proposal.md`, `test-strategy.md` §2 |
| **Requirement** | One sentence, paraphrased — not a quotation | The source named by the Req ID |
| **Related INV** | `INV-nn` from `test-strategy.md` §2, or `—` | `test-strategy.md` §2 |
| **Technique** | Technique name plus the exact catalog row, linked into `test-design-techniques.md` (`ST` = State Transition §2, `DT` = Decision Table §3, `BVA` = Boundary Value / Equivalence Partitioning §4, `PW` = Pairwise §5). `none catalogued` = the requirement is functional but no technique in that file covers it (a gap, listed in §4.3 — this file never invents one). `N/A — <why>` = the technique catalog does not apply (a non-functional, structural, or pipeline row) | `test-design-techniques.md` |
| **Test case(s)** | The real `Class#method` name(s) once the test exists. Until then the literal marker `chưa viết` ("not yet written") — never a guessed name. A trailing `⚠` means `test-plan.md` §1 names **no** test deliverable for this row's requirement at all (listed in §4.2) | Test code |
| **Level** | Primary level first: `Unit` · `Integration` · `Contract` · `E2E` · `Load` · `Resilience` · `Security`. A second level after `/` where a row needs both; the non-functional scenario or level document that supplies the mechanics after `—`. `—` alone = no level is defined anywhere in the corpus; `N/A — CI` = verified by the pipeline itself | `test-strategy.md` §2 Level column, `../test-levels/*.md`, `../non-functional/*.md` |
| **Phase** | 1–8, per `test-plan.md`. A trailing `†` means the phase is this file's assumption — `proposal.md` §15 and `test-plan.md` name none for it (for M-09/M-10/M-51 the assumption is inherited from `test-strategy.md` §2 and flagged there) | `test-plan.md` §1, §3 |
| **Status** | `Not started` · `Drafted` · `Passing` · `Flaky — investigate` (meaning in §3) | Updated with the test, per §3 |

**Guarantees:** the scheme is positional, so a Req ID is only as stable as `requirement.md` itself; §3 says what to do if that file gains or loses a bullet. Row keys (`M-nn`) do not depend on it.

### 2.2 The Matrix

**Rationale:** sorted by phase (1 → 8) so a row can be checked directly against `test-plan.md` §1's row for the same phase, not by hunting through a requirement-ordered list. Within a phase, rows follow the order they were added.

**Model:**

| Row | Req ID | Requirement | Related INV | Technique | Test case(s) | Level | Phase | Status |
|---|---|---|---|---|---|---|---|---|
| M-01 | REQ-2.11.5 | Users–roles–permissions mapping; a role's permission set can be reconfigured without code changes | INV-01 | [DT §3.4][dt] (has-permission column) | chưa viết | Integration | 1 | Not started |
| M-02 | REQ-2.11.6 | The JWT carries the resolved permission list; tampered, forged, `alg`-confused, expired or malformed tokens are rejected with no side effect | INV-01 | [DT §3.4][dt] row 1 (`403`); attack vectors S-1…S-6, `security-testing.md` §2.2 | chưa viết | Integration — `security-testing.md` §2.2 | 1 | Not started |
| M-03 | REQ-2.11.7 | Endpoint access is decided by `hasAuthority(permission)`, never by role name; `401` (bad token) and `403` (good token, no permission) are never confused | INV-01 | [DT §3.4][dt] rows 1–4; S-7, S-8, `security-testing.md` §2.2 | chưa viết | Integration | 1 | Not started |
| M-04 | REQ-1.3.3.1 | Organizer/Admin can create, edit, cancel and reschedule workshops | — | [ST §2][st] `workshops.status` (terminal `COMPLETED`/`CANCELLED` must-reject); [BVA §4][bva] title/speaker/location `VARCHAR(255)` | chưa viết | Unit / Integration | 1 | Not started |
| M-05 | REQ-1.3.1.1 | Student browses workshops by schedule and searches/filters by topic, speaker, location | — | [PW §5][pw] row 2 (list endpoints × `DRAFT`/`PUBLISHED` leak); search/filter semantics: none catalogued | chưa viết | Integration | 1 | Not started |
| M-06 | REQ-1.3.1.2 | Detail page shows speaker, location and seat availability; a `DRAFT` workshop is `404` to an under-permissioned caller | — | [DT §3.4][dt] row 4; seat-availability freshness: none catalogued | chưa viết | Integration | 1 | Not started |
| M-07 | REQ-1.3.1.1, REQ-1.3.1.2 | Web list and detail components render the Phase 1 endpoints' data | — | none catalogued (frontend component logic) | chưa viết | Unit — `e2e-testing.md` §1 (Vitest + RTL) | 1 | Not started |
| M-08 | REQ-2.4.1, REQ-2.4.2 | Low-change workshop data is served from the cache and refreshed when the source changes | — | none catalogued | chưa viết ⚠ | Integration (real Redis) | 1† | Not started |
| M-09 | REQ-1.3.6.1, REQ-1.3.6.2, REQ-2.10.1 | The nightly legacy export is ingested automatically through a sequential validate-then-write pipeline | INV-13 | [ST §2][st] `legacy_sync_runs.status` (`RUNNING`→`COMPLETED`/`FAILED`) | chưa viết | Integration | 1† | Not started |
| M-10 | REQ-1.5.7, REQ-2.10.2 | Malformed or duplicate records go to an error store; a few bad records never halt the batch | INV-13 | [ST §2][st] `legacy_sync_runs.status`; record-validity classes: none catalogued | chưa viết | Integration | 1† | Not started |
| M-11 | REQ-1.3.6.3 | The nightly sync does not disrupt users being served at the time | — | none catalogued | chưa viết ⚠ | Integration | 1† | Not started |
| M-12 | REQ-2.1.1, REQ-2.1.2 | Backend is organized into separated business modules; core logic is decoupled from infrastructure | — | none catalogued | chưa viết ⚠ | — | 1† | Not started |
| M-13 | REQ-3.3.1c | Configuration and secrets are kept out of source and injected at runtime | INV-18 | N/A — static check (S-20, Gitleaks, `security-testing.md` §2.1) | chưa viết | Security — static | 1 | Not started |
| M-14 | REQ-3.3.2 (steps 1–3) | On every push/PR to `main` the pipeline lints, runs unit tests and runs integration tests, and blocks the merge on failure | — | N/A — the pipeline itself | chưa viết | N/A — CI | 1 | Not started |
| M-15 | SC-09, REQ-3.2.1 | ≥80% line coverage on core business-logic services | — | N/A — measured gate (JaCoCo, `unit-testing.md` §3) | chưa viết | Unit — `unit-testing.md` §3 | 1 | Not started |
| M-16 | REQ-3.2.2 | Integration tests run against real PostgreSQL/Redis/RabbitMQ containers, never an in-memory database such as H2 | — | N/A — structural | chưa viết ⚠ | Integration — `integration-testing.md` §1 | 1 | Not started |
| M-17 | SC-08 | Every API response conforms to the consistent response/error shapes across success, conflict and failure paths | — | N/A — schema diff per endpoint | chưa viết | Contract — `contract-testing.md` §1 | 1 | Not started |
| M-18 | REQ-1.3.1.3a | Free workshop: the seat is confirmed immediately on registration and the payment gateway is never touched | — | [ST §2][st] `registrations.status`; [PW §5][pw] row 1 (`is_paid=false` × any breaker state) | chưa viết ⚠ | Unit / Integration | 2 | Not started |
| M-19 | REQ-1.3.1.3b | Paid workshop: the seat is confirmed only after payment completes (includes unit tests for payment orchestration) | — | [ST §2][st] `registrations.status`, `payments.status` | chưa viết | Unit / Integration | 2 | Not started |
| M-20 | REQ-1.5.2, REQ-2.8.1, REQ-2.8.2, P11.2 | A client-generated idempotency key executes the operation exactly once; duplicates replay the original result; the key row commits in the same transaction as the effect | INV-02 | none catalogued (replay / in-flight race); concurrency shape `coding-standards.md` §4 | chưa viết | Integration | 2 | Not started |
| M-21 | REQ-2.8.3 | An idempotency key is retained only for a bounded window | INV-02 | [BVA §4][bva] TTL-boundary shape — no catalogued row for this window | chưa viết | Integration | 2 | Not started |
| M-22 | REQ-2.3.2, ARC-INV-03 | Read-only requests go to the Replica; consistency-sensitive reads (idempotency lookup, immediate post-write view) go to the Master (`architecture.md` §4.1) | INV-03 | none catalogued (routing); fault condition per `resilience-testing.md` §2.5 (WAL replay paused) | chưa viết | Integration — `resilience-testing.md` §2.5 | 2 | Not started |
| M-23 | REQ-1.3.1.5, REQ-1.5.4, REQ-2.10.5 | A payment stuck `PENDING` past 15 minutes is resolved by a scheduled sweep against the gateway status API; the webhook/sweep race resolves exactly once | INV-04 | [ST §2][st] `payments.status`; [DT §3.2][dt]; [BVA §4][bva] payment reconciliation threshold | chưa viết | Integration | 2 | Not started |
| M-24 | REQ-1.5.5, REQ-2.7.1, REQ-2.7.2, REQ-2.7.3, P11.3 | A gateway outage opens the circuit breaker, fails fast with a clear message, leaves browsing and check-in unaffected, and recovers automatically | INV-05 | [DT §3.3][dt]; [PW §5][pw] row 1 | chưa viết | Integration — `resilience-testing.md` §2.2 (scenario 1) | 2 | Not started |
| M-25 | ARC-INV-10a | The registration-confirmed outbox row commits atomically with the registration write — both or neither (`architecture.md` §4.3, §6.1; `CLAUDE.md`) | INV-10 | none catalogued (atomicity); fixture `test-data-management.md` §3 | chưa viết | Integration | 2 | Not started |
| M-26 | SC-05 | Zero duplicate charges under retried requests; zero payments left permanently unresolved | INV-02, INV-04 | N/A — rollup of M-20, M-21, M-23 | chưa viết | Integration | 2 | Not started |
| M-27 | SC-07 | A payment gateway outage causes zero cascading failure into browsing, check-in or reporting | INV-05 | N/A — rollup of M-24 (Kubernetes-layer half: M-64) | chưa viết | Integration | 2 | Not started |
| M-28 | REQ-1.3.1.7, REQ-1.5.1, REQ-2.6.1, P11.1 | Under contention exactly the available seats succeed; every other request is refused — full-stack variant (lock + version + constraint) | INV-06 | [DT §3.1][dt] rows 1–3; [BVA §4][bva] seat capacity | chưa viết | Integration | 3 | Not started |
| M-29 | REQ-2.6.4 | With the lock-acquisition step skipped (Redis otherwise reachable), the optimistic version check alone still holds 0% overbooking | INV-06 | [DT §3.1][dt] row 4 | chưa viết | Integration | 3 | Not started |
| M-30 | REQ-2.6.4 | A raw concurrent `UPDATE` bypassing the application cannot push `seats_taken` above `capacity` — the check constraint is the final backstop | INV-06 | [DT §3.1][dt] row 5; [BVA §4][bva] seat capacity (third class) | chưa viết | Integration | 3 | Not started |
| M-31 | REQ-2.6.3 | The transaction isolation level prevents incomplete or non-repeatable reads during seat reservation | INV-06 | none catalogued | chưa viết ⚠ | Integration | 3 | Not started |
| M-32 | REQ-2.6.2 | The seat lock is recognized across parallel application instances: two servers never both confirm the last seat | INV-06 | [DT §3.1][dt] (lock column) | chưa viết ⚠ | Integration (≥2 instances) | 3 | Not started |
| M-33 | REQ-1.3.1.4, REQ-1.5.3, REQ-2.10.4 | A seat left `RESERVED` past 15 minutes is released automatically and asynchronously, without blocking the registration path | INV-07 | [ST §2][st] `registrations.status` (`EXPIRED`→`CONFIRMED` must-reject); [BVA §4][bva] seat-hold TTL | chưa viết | Integration | 3 | Not started |
| M-34 | ARC-INV-08 | With Redis coordination unreachable, registration writes are rejected (`503`) and never proceed uncoordinated (`architecture.md` §4.2) | INV-08 | N/A — fault injection; the hand-off is in [DT §3.1][dt]'s note | chưa viết | Integration — `resilience-testing.md` §2.3 (scenario 2) | 3 | Not started |
| M-35 | REQ-2.10.3, P11.5 | A scheduled background job executes on exactly one instance at a time | INV-09 | [ST §2][st] `legacy_sync_runs.status` (`RUNNING`→`RUNNING` must-reject) | chưa viết | Integration | 3 | Not started |
| M-36 | REQ-3.2.3b | First authored K6 adversarial script (500 VUs vs. the last 10 seats), run once locally as a smoke check — **not** the evidence gate | INV-06 | N/A — `performance-load.md` §2.2 | chưa viết | Load — `performance-load.md` §2.2 | 3 | Not started |
| M-37 | REQ-1.3.2.1 | A successful registration triggers a confirmation notification by email and in-app | — | none catalogued | chưa viết ⚠ | Integration (providers stubbed) | 4 | Not started |
| M-38 | REQ-1.3.2.2, REQ-1.3.3.2 | Cancelling or rescheduling a workshop notifies every registered student automatically | — | [ST §2][st] `workshops.status` (`PUBLISHED`→`CANCELLED`) | chưa viết ⚠ | Integration | 4 | Not started |
| M-39 | REQ-1.3.2.3, REQ-2.1.3 | A new notification channel can be added without changing existing channels or other business modules | — | none catalogued | chưa viết ⚠ | — | 4 | Not started |
| M-40 | ARC-INV-11 | Every consumer is idempotent under redelivery: effects, not messages, are counted (`architecture.md` §4.3; `CLAUDE.md`) | INV-11 | none catalogued (redelivery); pattern of `integration-testing.md` §4 (explicit setup/teardown, real singleton RabbitMQ, `integration-testing.md` §1–§2) — that table has no INV-11 row, so it is applied by analogy | chưa viết | Integration — `integration-testing.md` §1, §4 | 4 | Not started |
| M-41 | ARC-INV-10b | A committed outbox row is actually published and consumed; none is stranded (`architecture.md` §4.3, §6.1) | INV-10 | none catalogued (relay); `integration-testing.md` §4's INV-10 pattern — `event_publications` fixture inserted in the same transaction as the business row (`test-data-management.md` §3) | chưa viết | Integration — `integration-testing.md` §4 | 4 | Not started |
| M-42 | REQ-1.3.1.6 | Every successful registration is issued a unique QR e-ticket, which the Student views in the Web Application | INV-12 | [BVA §4][bva] `ticket_code` `VARCHAR(64)`; uniqueness rule `test-data-management.md` §5 | chưa viết ⚠ | Integration | 5 | Not started |
| M-43 | REQ-2.9.1, REQ-2.11.3 | The device verifies a ticket offline; forged, tampered, wrong-algorithm or other-key tickets are rejected with no network call | INV-12 | [ST §2][st] `check_in_scans.outcome` (`REJECTED_INVALID`, scenario (a)); vectors S-10 V1–V7, `security-testing.md` §2.2 | chưa viết | Unit (mobile — runner not yet chosen) | 5 | Not started |
| M-44 | REQ-1.3.5.1, REQ-1.3.5.2, REQ-2.9.2 | Staff can scan with no connectivity; each scan is recorded durably with ticket, staff member, status and device timestamp | INV-12 | [BVA §4][bva] `device_id` `VARCHAR(100)` | chưa viết | Unit (mobile — Expo SQLite restart survival) | 5 | Not started |
| M-45 | REQ-2.9.3 | The offline scan history is uploaded automatically when connectivity returns; the server accepts a batch within its size bounds | INV-12 | [BVA §4][bva] `SyncScanBatchRequest.scans` (0 / 1 / 500 / 501) | chưa viết ⚠ | Integration | 5 | Not started |
| M-46 | REQ-1.5.6, REQ-2.9.4, P11.4 | Exactly one valid check-in per ticket; the earliest `scanned_at` wins regardless of device or arrival order | INV-12 | [ST §2][st] `check_in_scans.outcome` (`ACCEPTED`→`REJECTED_DUPLICATE`; the reverse must-reject) | chưa viết | Integration | 5 | Not started |
| M-47 | REQ-1.5.6 | A signature-authentic ticket whose registration was cancelled is rejected at reconciliation and produces no attendance row | INV-12 | [ST §2][st] `check_in_scans.outcome` (`REJECTED_INVALID`, scenario (b)); S-11 | chưa viết | Integration | 5 | Not started |
| M-48 | REQ-2.11.3 | The verification-key endpoint exposes public key material only — a device that can verify can never forge | INV-12 | N/A — S-12, `security-testing.md` §2.2 | chưa viết ⚠ | Integration | 5 | Not started |
| M-49 | REQ-1.3.3.3 | Organizers see registration counts and check-in rates per workshop | — | none catalogued | chưa viết ⚠ | Integration | 5† | Not started |
| M-50 | REQ-1.3.4.1, REQ-1.3.4.2 | An uploaded PDF brochure is summarized automatically and the summary appears on the workshop detail page | — | none catalogued | chưa viết ⚠ | Integration (MinIO; AI service stubbed) | 5† | Not started |
| M-51 | ARC-INV-19 | An Object Storage outage is isolated to the brochure flow; Registration, Payment and Check-in keep working (`architecture.md` §4.4) | INV-19 | N/A — fault injection | chưa viết | Integration — `resilience-testing.md` §2.6 (scenario 5) | 5† | Not started |
| M-52 | SC-06 | Exactly one valid check-in per ticket after offline-sync reconciliation, with zero data loss | INV-12 | N/A — rollup of M-43 … M-47 | chưa viết | Integration | 5 | Not started |
| M-53 | REQ-3.1.1 | A Correlation ID assigned at the entry point propagates through HTTP, RabbitMQ and log output | — | none catalogued | chưa viết | Integration | 6 | Not started |
| M-54 | REQ-2.11.4 | Audit events are recorded for registration, payment, check-in and workshop schedule changes | — | none catalogued | chưa viết ⚠ | Integration | 6† | Not started |
| M-55 | REQ-2.11.4, ARC-INV-14 | An audit-pipeline outage never blocks or fails the business operation; the publish failure itself surfaces as an operational alert (`architecture.md` §5.5) | INV-14 | N/A — fault injection | chưa viết | Integration — `resilience-testing.md` §2.7 (scenario 6; alert half blocked — no signal defined) | 6 | Not started |
| M-56 | REQ-3.1.2 | OpenTelemetry traces database queries, third-party API calls and RabbitMQ messages | — | none catalogued | chưa viết ⚠ | — | 6 | Not started |
| M-57 | REQ-3.1.3 | Actuator metrics are scraped by Prometheus and shown on Grafana dashboards | — | none catalogued | chưa viết ⚠ | — | 6 | Not started |
| M-58 | ARC-INV-15 | Migrations are versioned and an old pod version still reads the schema mid-rollout (`architecture.md` §6.2; `CLAUDE.md`) | INV-15 | N/A — migration compatibility check | chưa viết | Integration | 7 | Not started |
| M-59 | REQ-2.2.1, REQ-2.2.2, REQ-3.3.1a, REQ-3.3.1d | At least two API pods run behind the Ingress; an unhealthy pod is restarted automatically and traffic reaches only Ready pods | INV-16 | N/A — fault injection | chưa viết | Resilience — `resilience-testing.md` §2.8 (scenario 7) | 7 | Not started |
| M-60 | REQ-2.3.1, REQ-3.3.1b | PostgreSQL primary and replica run under a StatefulSet with stable identity; the replica stays in near-real-time sync | — | none catalogued | chưa viết ⚠ | — | 7 | Not started |
| M-61 | REQ-2.5.1, REQ-2.5.2 | Per-user and per-IP rate limits are enforced from shared state, so scaling out cannot multiply the budget | INV-17 | [BVA §4][bva] rate-limit rows (5 / 10 s per user, 100 / 60 s per IP); S-13, S-14 | chưa viết | Integration (≥2 instances) — `security-testing.md` §2.2 | 7 | Not started |
| M-62 | REQ-2.5.1 | The limit still holds through the real Ingress across all pods, and against header-rotation and credential-stuffing evasion | INV-17 | N/A — S-15, S-16, `security-testing.md` §2.2 | chưa viết | Security — `security-testing.md` §2.2 | 7 | Not started |
| M-63 | ARC-INV-20 | A plaintext connection to any pod in the mesh is refused (mTLS STRICT, `architecture.md` §5.4). **Pending — INV-20 is not in force** while the mesh runs PERMISSIVE; this row cannot pass until STRICT is enabled | INV-20 | N/A — S-19 | chưa viết | Security — `security-testing.md` §2.2 (S-19) | 7 | Not started |
| M-64 | REQ-1.5.5 | With the gateway dead and the breaker OPEN, every API pod stays Ready and the Ingress keeps serving (Kubernetes-layer half of scenario 1) | INV-05 | N/A — fault injection | chưa viết | Resilience — `resilience-testing.md` §2.2 (1b) | 7 | Not started |
| M-65 | ARC-INV-08 | With Redis (then RabbitMQ) stopped, every API pod stays Ready and the `dependencies` health group reports `DEGRADED` (Kubernetes-layer half of scenario 2) | INV-08 | N/A — fault injection | chưa viết | Resilience — `resilience-testing.md` §2.3 (2b) | 7 | Not started |
| M-66 | REQ-3.3.2 (step 4) | The application is packaged as a Docker image and pushed, as the pipeline's final step | — | N/A — the pipeline itself | chưa viết | N/A — CI | 7 | Not started |
| M-67 | REQ-2.11.1 | All client-to-system communication is encrypted in transit | — | none catalogued | chưa viết ⚠ | E2E (smoke over HTTPS) | 7† | Not started |
| M-68 | REQ-2.2.3, REQ-2.11.2 | Any application instance can serve any user's request; authentication depends on no per-instance state | — | none catalogued | chưa viết ⚠ | Integration (≥2 instances) | 7† | Not started |
| M-69 | REQ-1.3.1.1, REQ-1.3.1.2, REQ-1.3.1.3a, REQ-1.3.1.3b, REQ-1.3.1.6 | Student journey end to end: discover → register → pay → view the e-ticket | — | N/A — user journey | chưa viết | E2E — `e2e-testing.md` §3 | 7 | Not started |
| M-70 | REQ-1.3.3.1, REQ-1.3.3.3 | Organizer/Admin dashboard journey end to end: manage a workshop and read the reports | — | N/A — user journey | chưa viết | E2E — `e2e-testing.md` §3 | 7 | Not started |
| M-71 | SC-01 | 0% overbooking — zero seats sold or held beyond capacity across all load-test scenarios | INV-06 | N/A — post-run SQL, `performance-load.md` §4 | chưa viết | Load — `performance-load.md` §2.1, §2.2 | 8 | Not started |
| M-72 | SC-02, REQ-1.4.1, REQ-1.4.3, REQ-3.2.3a | The system stays available and correct at ~12,000 concurrent users within 10 minutes, 60% of them in the first 3 | — | N/A — `performance-load.md` §2.1 | chưa viết | Load — `performance-load.md` §2.1 | 8 | Not started |
| M-73 | SC-03 | Sub-second p95 on the seat-reservation and registration-confirmation endpoints under peak load | — | N/A — `performance-load.md` §4 | chưa viết | Load — `performance-load.md` §4 | 8 | Not started |
| M-74 | SC-04, REQ-1.4.2, REQ-3.2.3b | 500 VUs against the last 10 seats: exactly 10 succeed, every other request ends in a clear refusal (`409`, or `503` retried to `409`) | INV-06 | [DT §3.1][dt] (interpretation note under the table); `performance-load.md` §2.2 | chưa viết | Load — `performance-load.md` §2.2 | 8 | Not started |

**Guarantees:** the table is complete against §1's Guarantee — every INV, every `SC-nn`, every `P11.n` and every derived `REQ-` ID appears in at least one row (verified mechanically when this file was written; §5 records the totals). A `⚠` row is a requirement with no planned test, not a requirement that has been forgotten. No row's Status is above `Not started` at the time of writing, because no test exists.

### 2.3 Worked Example of Filled Rows (Illustrative Only)

**Rationale:** the main table is entirely `Not started`, so it never shows what a row looks like once its test exists. This subsection does — **using invented status values on purpose, so it must never be read as a report.** The method names are the illustrative examples already printed in `coding-standards.md` §3–§4; no test with these names has been written or run, and no row in §2.2 has been changed to match.

**Model:** two rows as they would read after their tests land.

| Row | Req ID | Requirement | Related INV | Technique | Test case(s) | Level | Phase | Status |
|---|---|---|---|---|---|---|---|---|
| M-28 *(illustration)* | REQ-1.3.1.7, REQ-1.5.1, REQ-2.6.1, P11.1 | Under contention exactly the available seats succeed — full-stack variant | INV-06 | [DT §3.1][dt] rows 1–3; [BVA §4][bva] seat capacity | `RegistrationConcurrencyIT#acceptsExactlyRemainingSeats_whenConcurrentRequestsExceedCapacity` | Integration | 3 | Passing *(example only)* |
| M-33 *(illustration)* | REQ-1.3.1.4, REQ-1.5.3, REQ-2.10.4 | A lapsed seat hold is released automatically | INV-07 | [ST §2][st] `registrations.status`; [BVA §4][bva] seat-hold TTL | `ExpirySweepTest#marksExpired_whenHoldLapsed` *(covers the comparison logic only — the end-to-end job path is still to be written)* | Integration | 3 | Drafted *(example only)* |

What the example shows: the **Test case(s)** cell holds `Class#method` exactly as it appears in the code; a row is `Drafted`, not `Passing`, while any part of what it claims — here the end-to-end job path — has no passing test behind it; and `Flaky — investigate` (not shown) is what a `Passing` row becomes the first time it fails intermittently.

**Skeleton for a new row** (copy, then fill; take the next free `M-nn`):

```
| M-nn | <Req ID(s)> | <one-sentence requirement> | <INV-nn or —> | <technique + linked catalog row, or "none catalogued" / "N/A — why"> | chưa viết | <Level — doc §> | <Phase> | Not started |
```

**Guarantees:** nothing in §2.3 is counted in §4 or §5, and nothing in it is evidence. If a real test is ever given one of these names, the row in §2.2 — not this subsection — is what changes.

---

## 3. Update Discipline

**Rationale:** a traceability matrix that is updated in bulk "before the review" is a document written from memory, and drifts from the tests it describes. This project has one developer, so there is no handoff to coordinate; the rule is only about *when* the row changes, which is the moment the evidence changes.

**Model:**

| Rule | Detail |
|---|---|
| **When, and by whom** | The row is updated **in the same pull request that adds, changes or removes its test** — never in a separate bookkeeping commit. The author of the test PR is responsible (in this project, the single developer). |
| **What changes** | Status, and the **Test case(s)** cell (real `Class#method` names, per `coding-standards.md` §2). A parameterized decision-table test is one name; its provider must have the same row count as the catalog table (`coding-standards.md` §5). |
| **Status meanings** | `Not started` — no test method exists for the row. `Drafted` — a test exists but is not yet green on `main` in CI at the level and infrastructure the row requires, or covers only part of what the row claims. `Passing` — every test named in the cell is green on `main` in CI at the Level this row states; an INV row marked Testcontainers `Y` in `test-strategy.md` §2 is not `Passing` on the strength of a Unit test with a mocked datastore. `Flaky — investigate` — the test has failed intermittently; the row is **not** `Passing` until the cause is found and fixed. |
| **Deleting or disabling a test** | A row backed by a deleted or `@Disabled` test returns to `Not started` or `Drafted` in the same PR. |
| **New requirement or invariant** | Add a row with the next free `M-nn`. A new INV in `test-strategy.md` §2 gets its row in the PR that adds the INV. |
| **Removed requirement** | Remove the row in the PR that removes the requirement; do not reuse its `M-nn`. |
| **`requirement.md` changes** | Req IDs are positional (§2.1). If a bullet is added, removed or reordered, re-derive the affected IDs and update every row that cites them in the same PR. This is the only case in which a Req ID changes. |
| **§4 and §5 are snapshots** | They are refreshed **at phase sign-off** (`release-sign-offs/phase-template.md` §4), not on every PR. The **Status** column in §2.2 is the live truth; §4 and §5 are a summary of it at a point in time. |
| **A quick recount** | The command below the table counts §2.2's rows by Status. |

```bash
# rows of §2.2 currently Not started (matches only real M-nn rows, not the §2.3 skeleton)
grep -c '^| M-[0-9][0-9] .*| Not started |$' docs/testing/reports/test-matrix.md
```

**Guarantees:** at any commit, a row's Status is derivable from that commit's tests — no row asserts a state the tree at that commit cannot back. A phase is not signed off while any row of that phase that carries an INV is anything but `Passing` (`release-sign-offs/phase-template.md` §4).

---

## 4. Coverage Gaps

**Rationale:** with every row `Not started`, the table itself is the backlog — but a 74-row table is a poor overview. This section groups the gaps three ways: what is simply unwritten, what has no planned test at all, and what has no technique. **All three lists are snapshots** (§3) and go stale as tests land; remove a row from §4.1 when its Status leaves `Not started`.

**Model:**

### 4.1 Not started, by phase

At this snapshot **all 74 rows** are `Not started`. By phase:

| Phase | Rows |
|---|---|
| 1 | M-01 … M-17 (17 rows) |
| 2 | M-18 … M-27 (10 rows) |
| 3 | M-28 … M-36 (9 rows) |
| 4 | M-37 … M-41 (5 rows) |
| 5 | M-42 … M-52 (11 rows) |
| 6 | M-53 … M-57 (5 rows) |
| 7 | M-58 … M-70 (13 rows) |
| 8 | M-71 … M-74 (4 rows) |

### 4.2 Rows with no planned test deliverable (`⚠`)

`test-plan.md` §1 names no test for these rows' requirements. Each needs a decision — write a test, move the requirement to another level, or accept it as untested and record that in a phase sign-off's Known Gaps (`release-sign-offs/phase-template.md` §5):

M-08, M-11, M-12, M-16, M-18, M-31, M-32, M-37, M-38, M-39, M-42, M-45, M-48, M-49, M-50, M-54, M-56, M-57, M-60, M-67, M-68 (21 rows).

None of these carries an invariant except M-31, M-32 (INV-06 — `REQ-2.6.3` and `REQ-2.6.2`), M-42, M-45 and M-48 (INV-12 — ticket issuance, client upload trigger, key exposure). Those five are the ones that most need an owner, because `test-plan.md` §2 forbids weakening an INV's proof.

### 4.3 Rows with no catalogued technique, or with no INV or SRS source

- **`none catalogued`** — the requirement is functional but `test-design-techniques.md` has no technique row for it: M-05 (search/filter semantics), M-06 (seat-availability freshness), M-07, M-08, M-10 (record-validity classes), M-11, M-12, M-20, M-21 (idempotency-window value), M-22, M-25, M-31, M-37, M-39, M-40, M-41, M-49, M-50, M-53, M-54, M-56, M-57, M-60, M-67, M-68 (25 rows; for M-05, M-06, M-10 and M-21 a technique covers part of the row and only the named part is uncovered). This file does not add techniques; the list is a report to the owner of `test-design-techniques.md`.
- **`ARC-INV`** — invariants with no bullet of `requirement.md` behind them (§2.1): INV-03 (the Master-routing refinement), INV-08, INV-10, INV-11, INV-14 (the fail-open half), INV-15, INV-19, INV-20 (M-22, M-25, M-34, M-41, M-40, M-55, M-58, M-51, M-63, M-65).
- **Success criteria with no INV:** `SC-02`, `SC-03`, `SC-08`, `SC-09` (M-72, M-73, M-17, M-15) — evidence for these is a load result or a CI gate, not an invariant.

**Guarantees:** every gap above is a fact about the corpus at this snapshot, reported here rather than repaired here — this file never adds an INV, a technique or a test plan entry to close one.

---

## 5. Reference Table

**Snapshot at time of writing — not updated automatically; refreshed at phase sign-off (§3).** No source code exists, so every status count below is a statement about an empty test suite.

| Item | Value | Source |
|---|---|---|
| Invariants in the catalog | 20 (INV-01 … INV-20; INV-20 pending, not in force) | `test-strategy.md` §2 |
| Success-criteria rows | 9 (`SC-01` … `SC-09`) | `proposal.md` §12 |
| `proposal.md` §11 subsections | 5 (`P11.1` … `P11.5`) | `proposal.md` §11 |
| `requirement.md` IDs derived | 81 (`REQ-…`) | §2.1 |
| Matrix rows | 74 (`M-01` … `M-74`) | §2.2 |
| Status | Not started 74 (100%) · Drafted 0 (0%) · Passing 0 (0%) · Flaky — investigate 0 (0%) | §2.2 |
| Rows by phase | 1: 17 · 2: 10 · 3: 9 · 4: 5 · 5: 11 · 6: 5 · 7: 13 · 8: 4 | §2.2 |
| Rows by primary level | Integration 45 · Unit 7 · Load 5 · Resilience 3 · Security 3 · E2E 3 · Contract 1 · `N/A — CI` 2 · no level defined (`—`) 5 (total 74) | §2.2 |
| Rows with no planned test (`⚠`) | 21 | §4.2 |
| Rows with an assumed phase (`†`) | 11 | §2.2 |

[st]: ../guidelines/test-design-techniques.md#2-state-transition-catalog
[dt]: ../guidelines/test-design-techniques.md#3-decision-table-catalog
[bva]: ../guidelines/test-design-techniques.md#4-boundary-value--equivalence-partitioning-catalog
[pw]: ../guidelines/test-design-techniques.md#5-pairwise-testing
