# Security Testing — UniHub Non-Functional Testing

This file is the physical-level completion of `../strategy/test-strategy.md` §2's security-shaped invariants — INV-01 (permission-based RBAC), the adversarial half of INV-12 (self-verifying tickets), INV-17 (shared-state rate limiting), INV-18 (secrets) and INV-20 (mesh mTLS — pending, not yet in force) — for the question that catalog and `test-plan.md` raise but don't answer: *which adversarial vectors are actually thrown at each guarantee, and which guarantees have no test or tool yet?* `../guidelines/test-design-techniques.md` already fixes the *outcome* semantics (the RBAC decision table §3.4, the `REJECTED_INVALID` transitions §2, the rate-limit boundary values §4); this file supplies the attack side and cross-references those, never restating them. Nothing here overrides `test-strategy.md`/`test-plan.md` — where this file and they appear to disagree, those two are the authority and this file has a bug.

**Scope, stated plainly:** this is **PoC-level security testing** — a targeted set of adversarial tests against the guarantees this design makes, plus an OWASP Top 10 coverage map. It is **not a penetration test**: no DAST scan, no fuzzing campaign, no credential brute-forcing, no infrastructure or device compromise beyond the specific items below (`proposal.md` §5.2 puts real production hardening out of scope).

**Out of scope, and where it actually lives:** the RBAC decision table and the `DRAFT`-workshop `404`-not-`403` rule (`../guidelines/test-design-techniques.md` §3.4, §5); the `check_in_scans.outcome` transition semantics (§2 there); rate-limit boundary values and window rollover (§4 there); the JWT and ticket designs themselves (`../../technical-design/platform/auth-and-identity.md`, `../../technical-design/modules/checkin.md`); behavior when Redis is down, including the rate limiter failing open (`resilience-testing.md` §2.3); anything about load (`performance-load.md`).

**Gating:** this file adds no CI gate. A row marked "Per-push" is blocking only because `../strategy/test-plan.md` already schedules that INV row's Integration test at that phase (`test-strategy.md` §2, §4 stage 3). Everything else is on-demand, manual, or static.

**Citation convention:** design files cited by bare name (`payment.md`, `registration.md`, `messaging.md`, `distributed-coordination.md`, `observability.md`, `auth-and-identity.md`, `checkin.md`, `workshop.md`, `notification.md`, `deployment-and-cicd.md`, `database-schema.md`) live under `../../technical-design/modules/` or `../../technical-design/platform/`; `architecture.md`, `proposal.md`, `requirement.md` and `CLAUDE.md` are at the repository's `docs/` root and root respectively.

---

## Table of Contents

- [1. Objectives](#1-objectives)
- [2. Threat Catalog](#2-threat-catalog)
- [3. Tooling](#3-tooling)
- [4. Guarantees](#4-guarantees)
- [5. Reference Table](#5-reference-table)

---

## 1. Objectives

**Rationale:** `architecture.md` §5.6 lists five security guarantees, each with a deliberate "Known Limit". A security test that "fixes" a known limit is scope creep into a documented trade-off (`CLAUDE.md` says to surface those, not silently change them); a security test suite that ignores the guarantees is decoration. So each guarantee gets adversarial tests, and each limit gets — at most — a characterization test that pins it as documented.

**Model — OWASP Top 10 coverage map** (2021 edition; **TODO: confirm the edition the project wants to map to**). "Partial" means only static findings or an indirect test exist; "non-blocking" means a scan reports but gates nothing; "Pending" means the guarantee is not yet in force, so the row cannot be claimed.

| OWASP category | Addressed by | Status |
|---|---|---|
| A01 Broken Access Control | Rows S-1…S-8 (JWT / INV-01), S-18 (ownership check); RBAC decision table and `DRAFT` non-leak in `test-design-techniques.md` §3.4/§5 | Covered |
| A02 Cryptographic Failures | S-3, S-4 (algorithm pinning), S-10 (ticket crypto), S-12 (no private key material shipped) | Covered |
| A03 Injection | Semgrep SAST (§2.1) — reports only | **Partial** — static findings only; the corpus states no injection-specific rule or dynamic test (search/filter parameters, client-supplied `Idempotency-Key` strings) |
| A04 Insecure Design | Architecture itself (`architecture.md` §5) | Not testable here |
| A05 Security Misconfiguration | S-20 (secrets — Gitleaks, blocking); S-19 (mesh mTLS) | **Partial** — S-20 is covered; S-19 is **pending** until the Mesh mTLS Mode ADR's three preconditions are met and `PeerAuthentication` is STRICT (`deployment-and-cicd.md`) — not Covered until then |
| A06 Vulnerable & Outdated Components | OWASP Dependency-Check (§2.1) | Covered by a non-blocking scan — it reports, it does not gate |
| A07 Identification & Authentication Failures | S-5, S-6, S-9, S-16 | Covered |
| A08 Software & Data Integrity Failures | S-10, S-11 (ticket integrity), S-17 (webhook signature) | Covered |
| A09 Security Logging & Monitoring Failures | INV-14 in `resilience-testing.md` §2.7 — its alert clause is currently untestable | Partial |
| A10 Server-Side Request Forgery | — | No attack surface found: no user-supplied URL is fetched server-side (brochure keys are server-generated, `workshop.md` §5; uploads go browser → S3 directly) |

**Guarantees:** every guarantee in `architecture.md` §5.6's table maps to at least one row in §2 or is listed there as an open item — none is silently absent.

---

## 2. Threat Catalog

### 2.1 Static analysis, dependency checking, secret scanning

**Rationale:** the corpus had no SAST tool, no dependency-vulnerability tool and no secret scanner, and the only automated code check in `deployment-and-cicd.md` §5's pipeline is `lint` (Checkstyle + Spotless — style, not security). Three tools were chosen in a review and wired into that pipeline as jobs — Gitleaks **blocking**, Semgrep and Dependency-Check **non-blocking**; the reasoning and the conditions for promoting each to blocking are the Security Scan Jobs ADR there, not repeated here.

**Model:**

| Need | Tool | CI job | Maps to | Status |
|---|---|---|---|---|
| SAST (Java, TypeScript) | Semgrep — rule sets `p/java`, `p/typescript`, `p/owasp-top-ten` (starting values) | `sast-semgrep` | A03 (partial), A01, A05 | Non-blocking |
| Dependency vulnerabilities (Gradle, npm) | OWASP Dependency-Check (needs the `NVD_API_KEY` repository secret) | `dependency-check` | A06 | Non-blocking |
| Secret scanning over full history | Gitleaks | `secret-scan-gitleaks` | INV-18, A05 | **Blocking** |

**Guarantees:** Gitleaks is a blocking gate (`build-and-push` needs it), so INV-18's detection is enforced from the first push; Semgrep and Dependency-Check are non-blocking, so none of *their* rows may be quoted as enforcement — they report. What each tool scans is limited to what exists in the repository at the time (no application code exists yet, per `CLAUDE.md`'s Project Status).

### 2.2 Adversarial catalog

| # | Threat | How it is tested | Expected | INV | Level / Runs |
|---|---|---|---|---|---|
| **Auth JWT** — target `POST /api/v1/workshops` (requires `WORKSHOP_CREATE`) with an otherwise-valid body, and assert no workshop row was created | | | | | |
| S-1 | Privilege escalation by editing the payload | Take a Student's token, add `WORKSHOP_CREATE` to `permissions[]`, keep the original signature | `401`, no side effect (`architecture.md` §5.2: contents can't be altered without invalidating the signature) | INV-01 | Integration; per-push (Phase 1 exit criterion) |
| S-2 | Token forged with an attacker-chosen secret | Sign a fully-privileged token with a different HS256 key | `401` | INV-01 | Integration; per-push |
| S-3 | `alg: none` | Unsigned token, `alg` set to `none` | `401` — rejected before signature verification (`auth-and-identity.md` §3) | INV-01 | Integration; per-push |
| S-4 | Algorithm confusion | Header claims another algorithm (a different HMAC size, or an asymmetric one) | `401` — the verifier pins `HS256` (§3) | INV-01 | Integration; per-push |
| S-5 | Expired token | `exp` in the past | `401` | INV-01 | Integration; per-push |
| S-6 | Malformed / empty bearer | Truncated, non-JWT, empty | `401` | INV-01 | Integration; per-push |
| S-7 | Role name honored instead of permissions | A **correctly signed** token carrying `role: "ORGANIZER"` and `permissions: []` | `403` — role plays no part at request time (`architecture.md` §5.1). Together with S-1…S-6, asserts `401` (bad token) and `403` (good token, no permission) are never confused | INV-01 | Integration; per-push |
| S-8 | Verification key endpoint reachable without the permission | Student token on `GET /api/v1/checkin/verification-key` | `403` (`checkin.md` §6 gates it on `CHECKIN_SCAN`) | INV-01 | Integration; on-demand |
| S-9 | *Characterization of a deliberate limit* | Suspend a user: a fresh login is refused; a token issued before suspension keeps working until its `exp` | Documented behavior, pinned so nobody "fixes" it silently (`architecture.md` §5.2, §5.6; `auth-and-identity.md` §4) | — | Integration; on-demand |
| **E-ticket (QR)** | | | | | |
| S-10 | Forged or tampered ticket, verified on-device | Vectors below — every one must be rejected offline, instantly, recorded `REJECTED_INVALID` scenario (a) (`test-design-techniques.md` §2), no network call | Rejected (`checkin.md` §6, §8) | INV-12 | Unit (mobile test runner — tool not yet chosen, `test-plan.md` Phase 5); **no CI job exists for mobile tests — TODO** |
| S-11 | Genuine ticket, workshop since cancelled | Real signature, registration cancelled before sync | Accepted at scan (the documented authenticity-vs-validity gap), `REJECTED_INVALID` at reconciliation — scenario (b) of `test-design-techniques.md` §2 — and **no `attendances` row** | INV-12 | Integration; per-push (Phase 5) |
| S-12 | Compromised device yields signing capability | Inspect `GET /api/v1/checkin/verification-key`'s JWK | Public members only — no private-key member (`d`); a device that can verify can never forge (`architecture.md` §5.3) | INV-12 | Integration; on-demand |
| **Rate limiting** | | | | | |
| S-13 | Per-user budget bypassed by scaling out | Two application instances, one Redis; alternate 6 registration attempts from one user between them | 5 accepted in total, the 6th `429` with `Retry-After` — a per-instance limiter would allow 10 (`distributed-coordination.md` §3; boundary values in `test-design-techniques.md` §4) | INV-17 | Integration, ≥2 instances; per-push (Phase 7) |
| S-14 | Per-IP budget bypassed by scaling out | Same, 101 requests from one IP split across the two instances | 100 accepted, the 101st `429` | INV-17 | Integration, ≥2 instances; per-push (Phase 7) |
| S-15 | Same, through the real Ingress | On `overlays/loadtest` (3 pods) via `unihub.local`: 6 rapid registration attempts, one user | The 6th is `429` regardless of which pod served each | INV-17 | Full topology; Phase 7 manual |
| S-16 | Rate-limit evasion and credential stuffing | (a) Rotate `X-Forwarded-For` on the per-IP limiter; (b) exceed 100 login attempts/60 s from one IP | (a) budget not reset by a client-supplied header — **TODO: how `ipAddress` is derived behind the Ingress is not specified**; (b) `429` (`distributed-coordination.md` §3 names login abuse as the per-IP limiter's purpose) | INV-17 | Integration; on-demand |
| **Other architecture-derived** | | | | | |
| S-17 | Forged payment webhook | Invalid signature; body altered after signing; missing header | `400`, nothing read from the body as trusted (`payment.md` §7 step 1). A *valid* replay is a `200` no-op — already `test-design-techniques.md` §3.2 | — | Integration; **Phase 2** — owned by `test-matrix.md` M-79 (Phase 2 checklist D13, decided 2026-10-14); `PaymentWebhookSignatureIT`, `StripeWebhookVerifierTest` (2026-10-21) |
| S-18 | Reading another student's e-ticket | Student B's token on `GET /api/v1/registrations/{A's id}` | `403`, QR payload not disclosed (the contract: ownership-checked, not permission-checked) | — | Integration; on-demand |
| S-19 | Plaintext hop inside the mesh | From a pod in a namespace **without** sidecar injection, open a plaintext connection to `backend-api-service:8080` and to the PostgreSQL, Redis, RabbitMQ and MinIO ports | Refused — once `PeerAuthentication` is STRICT (`deployment-and-cicd.md` §2; `architecture.md` §5.4). A pod *with* a sidecar connects normally. **Pending: under the interim PERMISSIVE mode this test is expected to fail to refuse plaintext, and the row must not be marked Covered until the three preconditions (Ingress path, Flyway Job sidecar exit, observability stack) are met** | INV-20 (pending) | Full topology; Phase 7 manual |
| S-20 | Secret in source or config | Gitleaks over full history (`secret-scan-gitleaks`) + review; runtime check that shipped manifests hold placeholders only | None found (`CLAUDE.md`) | INV-18 | Static — Gitleaks, blocking (§2.1) |

**S-10's vectors** — each expected `REJECTED_INVALID` (a): (V1) payload edited, signature kept; (V2) signature stripped or truncated; (V3) valid signature from an attacker's own ES256 key; (V4) `alg: none`; (V5) header claiming a symmetric algorithm, the public key material used as the HMAC secret (rejected by pinning `algorithms: ['ES256']`, `checkin.md` §6); (V6) malformed or non-JWS input; (V7) a ticket signed under the previous key after the device refreshed its single cached key (`checkin.md` §6 — rotation revokes every old ticket).

**Deliberately not a vector:** a photographed or copied authentic QR passes the signature check by design — a copying problem, not a forgery problem (`architecture.md` §5.3). It is defended by earliest-timestamp reconciliation, tested under INV-12 (`test-design-techniques.md` §2), not here.

**Guarantees:** S-1…S-7 always assert the *status* (`401` vs `403`) and the *absence of side effects*, so a bypass that returns `200` or half-executes fails visibly.

---

## 3. Tooling

**Rationale:** the adversarial tests need to build tokens the server should reject, which needs the same JOSE tooling the design already uses — not a new dependency.

**Model:**

| Need | Mechanism | New dependency? |
|---|---|---|
| Forge/edit auth JWTs (S-1…S-7) | `nimbus-jose-jwt`, already the server's JOSE library (`auth-and-identity.md` §3), used in a test-only helper that builds arbitrary headers and claims | No |
| Forge/edit QR tickets on-device (S-10) | `jose`, already the Check-in Client's library (`CLAUDE.md`), plus the mobile test runner — not yet chosen (`test-plan.md` Phase 5) | Runner: open item |
| ≥2 application instances (S-13, S-14) | A second Spring Boot application context on another port sharing the same Testcontainers. This is a new integration-test shape: `integration-testing.md` §2's `IntegrationTestSupport` boots one context. **TODO: extend it or add a sibling base class.** | No |
| Full-topology rows (S-15, S-19) | `kubectl` against Minikube | No |
| SAST, dependency check, secret scanning | Semgrep and OWASP Dependency-Check (non-blocking); Gitleaks (blocking) — CI jobs (§2.1) | Chosen in review; the CI jobs are in `deployment-and-cicd.md` §5 |

**Guarantees:** no row depends on a tool the corpus hasn't already adopted, except the rows explicitly marked open.

---

## 4. Guarantees

- **PoC scope, stated in the file** — a targeted adversarial set plus an OWASP map, not a penetration test; the OWASP map shows partial coverage (A03, A05, A09) and non-blocking coverage (A06) instead of hiding them.
- **Known limits are pinned, not fixed** — S-9 characterizes the revocation lag, and copied-QR reuse is excluded on purpose; a change to either is a design decision surfaced to the user (`CLAUDE.md` Boundaries), not a test-suite side effect.
- **Every INV keeps its own authority** — INV-01, INV-12 and INV-17 rows cross-reference `test-design-techniques.md` for outcome semantics instead of restating them.
- **No invented tooling or behavior** — where the corpus fixes no tool or setting (client-IP derivation, the mobile test runner), the row says TODO.
- **Nothing is marked as passing by inspection** — S-20 is a static check, not a runtime test, and says so.

---

## 5. Reference Table

| Item | Value | Section |
|---|---|---|
| Scope | PoC-level adversarial testing + OWASP Top 10 map; not a penetration test | Intro, §1 |
| Auth JWT vectors | S-1…S-7 (edit, wrong secret, `none`, alg confusion, expired, malformed, role-name) | §2.2 |
| Ticket vectors | S-10 (V1–V7), S-11, S-12 | §2.2 |
| Rate-limit vectors | S-13…S-16 | §2.2 |
| INV covered | INV-01, INV-12 (adversarial half), INV-17, INV-18; INV-20 — pending, not yet in force | §2.2 |
| Rows with no INV | S-9, S-17, S-18 | §2.2 |
| Open tool decisions | Mobile test runner; Semgrep rule sets; promoting Semgrep/Dependency-Check to blocking (`deployment-and-cicd.md` §5) | §2.1, §3 |
| Open spec gaps | Client-IP derivation; A03 dynamic-injection rule; OWASP edition | §2.2, §1 |
| Full topology needed | S-15, S-19 only (S-19 pending the STRICT preconditions) | §2.2 |
