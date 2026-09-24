# Performance & Load Testing — UniHub Non-Functional Testing

This file is the physical-level completion of `../strategy/test-strategy.md` §3–§4's Load / adversarial-concurrency row and its §7 Reference Table entry — the two scenarios named there (12,000 CCU / 10-minute surge; 500 VUs vs. the last 10 seats) — and of `test-plan.md` Phase 3's first K6 adversarial draft and Phase 8's full-topology runs, for the question those files leave open: *what exact K6 profile, identities, environment and pass/fail thresholds turn "prove peak load capacity" into a repeatable run?* Nothing here overrides `test-strategy.md`/`test-plan.md`/`proposal.md` §12 — where this file and they appear to disagree, those are the authority and this file has a bug.

**Out of scope, and where it actually lives:** the anti-overbooking layered proof itself — the full-stack / lock-disabled / DB-only variants are JUnit concurrency tests, the Phase 3 evidence gate, and K6 cannot disable a layer from outside (`../guidelines/test-design-techniques.md` §3.1, `../guidelines/coding-standards.md` §4, `test-plan.md` Phase 3); fault injection while the system is under load (`resilience-testing.md`); adversarial security vectors (`security-testing.md`); the never-call-real-Stripe/S3 rule that still binds at full scale (`../guidelines/mocking-guidelines.md` §1); the Kustomize overlays and Minikube deploy mechanics (`../../technical-design/platform/deployment-and-cicd.md` §3–§4).

**Gating:** none. Load testing never runs in CI (`test-strategy.md` §3–§4). It is a **dedicated session** on the full Minikube topology, an evidence gate for Phase 8 sign-off only.

**Citation convention:** design files cited by bare name (`payment.md`, `registration.md`, `messaging.md`, `distributed-coordination.md`, `observability.md`, `auth-and-identity.md`, `checkin.md`, `workshop.md`, `notification.md`, `deployment-and-cicd.md`, `database-schema.md`) live under `../../technical-design/modules/` or `../../technical-design/platform/`; `architecture.md`, `proposal.md`, `requirement.md` and `CLAUDE.md` are at the repository's `docs/` root and root respectively.

---

## Table of Contents

- [1. Objectives](#1-objectives)
- [2. Scenario Definitions](#2-scenario-definitions)
- [3. Environment & Execution Checklist](#3-environment-execution-checklist)
- [4. Metrics & Thresholds](#4-metrics-thresholds)
- [5. Guarantees](#5-guarantees)
- [6. Reference Table](#6-reference-table)

---

## 1. Objectives

**Rationale:** four of `proposal.md` §12's nine rows can only be evidenced under real load; the other five (payment consistency, check-in integrity, fault isolation, contract consistency, coverage) are proven elsewhere and are deliberately not this file's job.

**Model:**

| `proposal.md` §12 row | Success criterion (as written there) | Scenario here | Evidence |
|---|---|---|---|
| Peak Load Capacity | Available and correct at ~12,000 concurrent users in a 10-minute window, 60% in the first 3 minutes | §2.1 Surge | Response classification + post-run invariants (§4) |
| Registration Latency | Sub-second (p95) for seat-reservation and registration-confirmation endpoints under peak load | §2.1 Surge | K6 p95 threshold (§4) |
| Concurrency Correctness | Exactly the remaining seats succeed; all others get a clear, immediate "unavailable" | §2.2 Adversarial | Outcome counts + SQL (§4) |
| Overbooking | 0% — zero seats sold or held beyond capacity across all load-test scenarios | §2.1 and §2.2 | Post-run SQL, INV-06 (§4) |

**Guarantees:** a Phase 8 sign-off needs every threshold in §4 evidenced from a run made under §3's checklist. A run that skipped the checklist is a smoke run, not evidence.

---

## 2. Scenario Definitions

### 2.1 Scenario A — Registration surge

**Rationale:** `requirement.md` §1.4 and `proposal.md` §12 give exactly one shape: ~12,000 concurrent users within the first 10 minutes, ~60% of that traffic within the first 3 minutes. That split is the only ramp the corpus sources, so it is the only one used. Arithmetic on it: 60% of 12,000 = **7,200** users in minutes 0–3 (40 per second); the remaining **4,800** over minutes 3–10 (420 s, 80 per 7 s).

**Model:** two sequential K6 `constant-arrival-rate` scenarios, one *iteration* = one user's journey.

| Parameter | Value | Source |
|---|---|---|
| Users | 12,000, one identity each (§2.3) | `requirement.md` §1.4 |
| Phase 1 | 40 iterations/s for 3 min = 7,200 | 60% in first 3 min |
| Phase 2 | 80 iterations per 7 s for 7 min = 4,800 | remaining 40% |
| Hot workshops | 8–12 concurrent, capacity ~60 each — **TODO: `requirement.md` §1.1/§1.4 give these only as illustrative ("e.g., 60 seats"); confirm the seeded count and capacities. Total seats decide the 2xx-to-`409` ratio and must be recorded with every result.** | `requirement.md` §1.1, §1.4 |
| Journey | `GET /api/v1/workshops` → `POST /api/v1/registrations` with an `Idempotency-Key`; **TODO: journey mix — detail view, think time, free/paid split, whether paid journeys call `POST /api/v1/payments/charge` — has no source.** The gateway is always the WireMock stub (`mocking-guidelines.md` §1); **TODO: stub latency** | — |
| Interpretation | **TODO: "12,000 concurrent users" is read here as 12,000 distinct users arriving on the 60/3-minute curve. If it means 12,000 *simultaneously active* sessions, the executor changes to `ramping-vus` and the arithmetic above no longer applies. `requirement.md` §1.4 reads both ways; confirm with the author of that section — this file must not choose.** | — |

```javascript
import http from 'k6/http';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';

const tokens = new SharedArray('tokens', () => JSON.parse(open('./tokens.json'))); // §2.3, minted just before the run
const BASE = 'https://unihub.local';                                               // Ingress host, deployment-and-cicd.md §2

export const options = {
  hosts: { 'unihub.local': __ENV.INGRESS_IP },
  insecureSkipTLSVerify: true,                       // Ingress serves the unihub-tls Secret's certificate
  scenarios: {
    surge_first_3_min: {
      executor: 'constant-arrival-rate', exec: 'journey',
      rate: 40, timeUnit: '1s', duration: '3m',      // 7,200 = 60% of 12,000
      preAllocatedVUs: 500,                          // TODO: rate x journey duration (Little's law)
      maxVUs: 12000,
    },
    surge_remaining_7_min: {
      executor: 'constant-arrival-rate', exec: 'journey', startTime: '3m',
      rate: 80, timeUnit: '7s', duration: '7m',      // 4,800 = 40% of 12,000
      preAllocatedVUs: 200,                          // TODO
      maxVUs: 12000,
    },
  },
  thresholds: {
    'http_req_duration{endpoint:createRegistration}': ['p(95)<1000'], // proposal.md §12: sub-second p95
    dropped_iterations: ['count==0'],                                  // the generator kept the arrival rate
    // TODO: error-rate threshold — no source (§4)
  },
};

export function journey() {
  const offset = exec.scenario.name === 'surge_first_3_min' ? 0 : 7200;
  const i = offset + exec.scenario.iterationInTest;  // unique per user across both phases
  const auth = { Authorization: `Bearer ${tokens[i]}` };
  http.get(`${BASE}/api/v1/workshops`, { headers: auth, tags: { endpoint: 'listWorkshops' } });
  http.post(`${BASE}/api/v1/registrations`,
    JSON.stringify({ workshopId: __ENV.WORKSHOP_IDS.split(',')[i % __ENV.WORKSHOP_IDS.split(',').length] }),
    { headers: { ...auth, 'Content-Type': 'application/json', 'Idempotency-Key': `${__ENV.RUN_ID}-${i}` },
      tags: { endpoint: 'createRegistration' } });
}
```

Where the script file lives is **TODO** — no repository location for load-test scripts is fixed anywhere in the corpus (`proposal.md` §17 names them a deliverable without a path). The snippet above is the specification of the script, not a committed file.

**Guarantees:** the ramp is the sourced 60%/3-minute curve and nothing else; every parameter without a source is a visible TODO, not a chosen number.

### 2.2 Scenario B — Adversarial: 500 VUs vs. the last 10 seats

**Rationale:** `requirement.md` §3.2 defines it: 500 VUs competing for the last 10 seats of one workshop, expecting `409` (or an accurate "sold out") for later arrivals, with neither Distributed Locking nor Optimistic Locking bypassed. **Relationship to Phase 3:** `test-plan.md` Phase 3's evidence gate is the JUnit/Testcontainers concurrency suite; alongside it, Phase 3 authors *this K6 script* and runs it once locally as a smoke check only. Phase 8 reruns the **same script** at full topology — same logic, different scale and environment. K6 only ever exercises the full-stack variant; the lock-disabled and DB-only proofs exist only in the JUnit suite.

**Model:**

| Parameter | Value |
|---|---|
| Executor | `per-vu-iterations`, 500 VUs × 1 iteration, started together |
| Workshop | one, capacity C with `seats_taken = C − 10` (`../guidelines/test-data-management.md` §2's boundary shape); `is_paid=false` so seats are *sold*, isolating the seat logic from payment. **TODO: a paid variant (10 *held* seats) if wanted.** |
| Contenders | 500 distinct seeded users (§2.3) |
| First response | Classified, never asserted as "all `409`" — see below |
| Retry | A `503` is retried after its `Retry-After` with the **same** `Idempotency-Key` until a definitive answer, capped at **TODO** attempts |

**Why the first response is not asserted as "exactly 10 × 2xx, 490 × `409`":** `registration.md` §2 makes lock contention (`tryLock` 2 s wait) a distinct `503 + Retry-After`, not a `409`, so with 500 contenders on one workshop a share of losers legitimately get `503` first. `requirement.md` §3.2 anticipates only `409`. This project's approved reading — `503` + `Retry-After`, retried until definitive, satisfies "clear, immediate" — is recorded in `../guidelines/test-design-techniques.md` §3.1.

```javascript
scenarios: { adversarial: { executor: 'per-vu-iterations', vus: 500, iterations: 1, maxDuration: '2m', exec: 'contend' } },
thresholds: {
  'outcome{status:201}': ['count==10'],   // exactly the remaining seats
  'outcome{status:409}': ['count==490'],  // everyone else, after retry-until-definitive
},
```

(`outcome` is a custom `Counter` incremented once per VU with its final status tag.)

**Guarantees:** the pass condition is the SQL invariant of §4, not the K6 counters alone; the counters must agree with it.

### 2.3 Test identities — 12,000 valid JWTs

**Rationale:** creating 12,000 users through the API would add write load to the very system being measured, and logging in 12,000 times adds password hashing and hits the login rate limit. The auth JWT is verified by signature and expiry alone with no database or Redis lookup (`auth-and-identity.md` §3), so a token needs no server-side session — but the *registration* still needs a real `users` row for its foreign key.

**Proposed mechanism (confirm before use):**
1. **Seed rows straight into the load-test Postgres Master** (`generate_series`), not through the API: 12,000 `users` with `status = 'ACTIVE'`; `password_hash` and `legacy_student_id` are nullable (`database-schema.md` §2.1), so neither is needed. Plus the seeded workshops. **TODO: verify the required column set against the §2.1/§3.1 DDL.**
2. **Mint the tokens offline**, immediately before the run, with a small script that signs HS256 with the load-test cluster's own JWT secret read at run time from the cluster's Secret — never written into the repository or any committed file (INV-18). Claims exactly as `auth-and-identity.md` §2: `sub` (the seeded `users.id`), `permissions[]`, `iat`, `exp`. **OPEN — deliberately not decided here: which permission codes a Student token carries. `deployment-and-cicd.md` §4's seed SQL bundles *no* permissions for the `Student` role, which would mean `permissions: []`, and `createRegistration` needs none (the contract's "any authenticated user"). But the seed migration file does not exist yet, so mint from the real seed once it does rather than from this reading.** Write the result to `tokens.json` for K6's `SharedArray`.
3. **Token lifetime is 30 minutes** (`auth-and-identity.md` §4) with no refresh: mint no earlier than ~15 minutes before start, or the last arrivals of a 10-minute surge could hold an expired token and get `401`. Verify `exp` covers the whole run before starting.

Rejected: a test-only login/impersonation endpoint — it would add application code and attack surface for a measurement convenience, which `CLAUDE.md`'s Boundaries argue against. **This is a proposal, not a decision — the user chooses the mechanism.**

**Guarantees:** every VU authenticates with a real, correctly-signed token for a real user row; no measured request is spent on authentication setup.

### 2.4 Mesh on vs. mesh off

**Rationale:** Istio's sidecars add CPU and a per-hop latency cost on every pod including the registration hot path (`architecture.md` §5.4's stated trade-offs; `proposal.md` §16). Without a comparison, a p95 miss cannot be attributed to the application or to the mesh.

**Model:** two runs of the identical script, seed and overlay:

| Run | Mesh | Role |
|---|---|---|
| R1 | On — namespace label `istio-injection: enabled` (`deployment-and-cicd.md` §2), the system as designed | **The acceptance run.** Only R1 can evidence §12 |
| R2 | Off — set the label to `disabled` and restart every Deployment/StatefulSet in the namespace; verify no pod still has an `istio-proxy` container | Diagnostic baseline only |

Between runs, reset to an identical clean data state (§3). The mesh mode (`PERMISSIVE` today; STRICT is the target — `deployment-and-cicd.md` §2) is enforced by the sidecars, so it has nothing to enforce once they are removed from the `unihub` pods. Once STRICT is in force, **verify the Ingress → pod path still works with the sidecars removed before trusting R2.** Compare per-endpoint p50/p95/p99, response-status distribution, and server-side `process_cpu_usage`/`jvm_memory_used_bytes` (`observability.md` §3). R2 disables mTLS, so it is not the system as designed and never evidences a criterion. If R1 misses a threshold that R2 meets, the finding is the documented mesh cost of `architecture.md` §5.4 — **surface it to the user; do not treat it as a bug to fix silently.** Repeat count per configuration is **TODO** (a single run per side cannot separate mesh cost from run-to-run noise).

**Guarantees:** a p95 result always states which mesh configuration produced it.

---

## 3. Environment & Execution Checklist

**Rationale:** `proposal.md` §16 reserves the full topology at scale for dedicated sessions because it strains developer hardware, and `test-strategy.md` §5 schedules the load runs at Phase 8. Two environment facts decide whether the numbers mean anything.

**Model:**

*Where it runs:* Minikube with `overlays/loadtest` (3 API replicas, 1 Postgres replica, full-size resources, `deployment-and-cicd.md` §3), K6 **outside** the cluster — never an in-cluster K6, which would compete with the system under test. Outside the cluster is not outside the machine: if K6 and Minikube share one physical host they still share CPU and memory. `dropped_iterations` (§4) is the check that the generator kept up; also record host CPU/memory during a dry run. Reaching `unihub.local` from the host (e.g. `minikube tunnel`) is a setup step.

*Before every measured run — all must hold:*

- [ ] `make deploy-loadtest` completed, migration Job finished (`deployment-and-cicd.md` §4); 3 `backend-api` pods `Ready`, replica `Ready`.
- [ ] Data reset to a known state: seeded users and workshops only; zero `registrations`, `payments`, `check_in_scans`, `idempotency_keys`; `seats_taken` at its seeded value. **TODO: the reset mechanism (snapshot restore vs. re-seed) is unspecified.**
- [ ] Redis clean of `unihub:ratelimit:*` and `unihub:lock:*` keys left by a previous run; cache state deliberately chosen and recorded — **TODO: cold or warm catalog cache for the surge.**
- [ ] `paymentGateway` breaker `CLOSED` (`resilience4j_circuitbreaker_state`, `observability.md` §4); WireMock stub up. **TODO: stub placement** (if in `unihub`, it must be sidecar-injected once the mesh is STRICT — the target, not yet in force).
- [ ] RabbitMQ queues empty, `unihub.parking-lot` = 0; `unihub_outbox_oldest_pending_age_seconds` ≈ 0; `unihub_postgres_replica_lag_seconds` ≈ 0.
- [ ] Prometheus scraping, Grafana dashboards up; alert state cleared so any alert that fires is attributable to this run.
- [ ] Tokens minted within the lifetime window (§2.3), `RUN_ID` set fresh (so idempotency keys from a prior run can't collide).
- [ ] **Per-IP rate limit handled deliberately — the most likely silent invalidator.** All K6 traffic arrives from one source, but the per-IP limiter allows 100 requests per 60 s (`distributed-coordination.md` §3): the surge would be almost entirely `429`, and the adversarial run would let only ~100 of 500 contenders reach the seat logic. `requirement.md` §3.2 defines the adversarial test as exercising the two locking layers, so the limiter must not intercept it. **TODO: decide how — spread source addresses, or raise/disable the per-IP limit in the load-test configuration (a limiter is abuse prevention, not a correctness mechanism, `distributed-coordination.md` §3). How the application derives `ipAddress` behind the Ingress is not specified. Record the choice with the results.**
- [ ] A short warm-up run at low rate discarded before the measured run (this file's methodology choice; the JIT skews a cold JVM).

*After the run:* run §4's SQL immediately, then again after the longest hold (15 min) plus the retry chain has drained (`messaging.md` §4), and confirm `unihub.parking-lot` is still 0 and no `RESERVED` holds remain that should have expired (INV-07 at scale).

**Guarantees:** a result is admissible only with the checklist signed off and the mesh configuration, seat totals, cache state and rate-limit handling recorded next to it.

---

## 4. Metrics & Thresholds

**Rationale:** `proposal.md` §12 fixes one number (sub-second p95) and otherwise speaks in absolutes ("0%", "exactly that number"). Anything else below is marked TODO rather than invented. The Grafana alert values in `observability.md` §6 (p95 > 500 ms warning / > 2 s critical, 5xx > 1% / > 5%) are alerting starting values "not load-tested against real traffic", not acceptance criteria, so they are recorded as cross-checks and not used as pass/fail.

**Model — response classification** (needed before any "error rate" means anything):

| Status | Class | Why |
|---|---|---|
| `201` | Success | Seat held or confirmed |
| `409` | Correct outcome | Sold out — the expected result for most of 12,000 users against ~720 seats; **never an error** |
| `503` + `Retry-After` | Documented transient | Seat-lock contention (`registration.md` §2) — counted and reported separately |
| `429` | Protective | Rate limit; expected to be ~0 once §3's rate-limit item is resolved — a non-zero count is a finding |
| `401`/`403` | Harness error | Seeded tokens or permissions are wrong — invalidates the run |
| `500`/`502`/`504`, timeout | Error | Unexpected |

**Model — thresholds:**

| Metric | Threshold | Source |
|---|---|---|
| p95 latency, `createRegistration` (client-side, K6) | < 1000 ms | `proposal.md` §12 |
| p95 latency, "registration-confirmation" endpoint | < 1000 ms — **TODO: `proposal.md` §12 names "seat-reservation and registration-confirmation endpoints"; confirm whether the second is `chargePayment`, the webhook, or the free `createRegistration` itself** | `proposal.md` §12 |
| Overbooking | 0 — both queries below return no rows | `proposal.md` §12, INV-06 |
| Adversarial outcome | Exactly 10 × `201`, 490 × `409` after retry-until-definitive | `proposal.md` §12, `requirement.md` §3.2 |
| Error rate (`500`/`502`/`504`/timeout ÷ all) | **TODO — no source** | — |
| "Remains available" during the surge | **TODO — no measurable definition in the corpus** | `proposal.md` §12 |
| `dropped_iterations` | 0 | This file — validates the generator |
| Latency for a `409` ("immediate") | **TODO — "immediate" is unquantified** | `proposal.md` §12 |

```sql
-- (a) the storage-layer invariant
SELECT id FROM workshops WHERE seats_taken > capacity;                          -- expect 0 rows
-- (b) held-or-sold never exceeds capacity, and the counter agrees with the rows
SELECT w.id FROM workshops w
LEFT JOIN registrations r ON r.workshop_id = w.id AND r.status IN ('RESERVED','CONFIRMED')
GROUP BY w.id, w.capacity, w.seats_taken
HAVING COUNT(r.id) > w.capacity OR COUNT(r.id) <> w.seats_taken;                -- expect 0 rows
```

(b)'s second clause follows from `registration.md` §1's rule that every seat-occupancy change commits in the same transaction as the `seats_taken` update. Run on the Master.

**Recorded, not gated:** server-side p95/p99 (`http_server_requests_seconds`), `hikaricp_connections_pending`, replica lag, queue depth per queue, outbox age, breaker state, and which Grafana alerts fired. **A Critical alert firing during an otherwise-passing run is surfaced to the user as a finding**, not filtered out. Latency is judged on K6's client-side measurement (it includes Ingress and mesh); server-side is for diagnosis. That split is this file's own decision.

**Guarantees:** no threshold in this table exists without either a `proposal.md`/`requirement.md` source or an explicit TODO.

---

## 5. Guarantees

- **Sourced ramp only** — the surge uses the sourced 60%-in-3-minutes curve; every other load parameter is a TODO the user fills, never a guess.
- **The safety check is SQL, not the client** — 0% overbooking is asserted against the Master's own state (INV-06), which K6's response counts must agree with but cannot replace.
- **No cross-contamination** — the K6 generator is external to the cluster, the gateway and object store are never real (`mocking-guidelines.md` §1), and the per-IP rate limiter is a decided, recorded setting rather than an accident.
- **Only the designed system evidences a criterion** — R1 (mesh on) is the acceptance run; R2 is diagnostic.
- **Never a merge gate** — this file adds nothing to CI.

---

## 6. Reference Table

| Item | Value | Section |
|---|---|---|
| Surge profile | 7,200 users in 3 min (40/s) + 4,800 in 7 min (80 per 7 s) = 12,000 in 10 min | §2.1 |
| Adversarial profile | 500 VUs × 1 iteration vs. 10 remaining seats of one workshop | §2.2 |
| Latency threshold | p95 < 1000 ms on seat-reservation (`createRegistration`) | §4 |
| Overbooking check | Two SQL queries on the Master, both empty | §4 |
| Identities | Rows seeded directly into Postgres; HS256 tokens minted offline from the cluster's own secret; 30-min TTL | §2.3 |
| Mesh comparison | R1 on (acceptance) vs. R2 off (diagnostic) | §2.4 |
| Environment | Minikube `overlays/loadtest`, K6 outside the cluster, dedicated session | §3 |
| INV covered | INV-06 (and INV-07 via the post-run settle check) | §4, §3 |
| Open items | Interpretation of "12,000 concurrent"; journey mix; seat totals; stub latency and placement; per-IP limiter handling; error-rate and availability thresholds; "registration-confirmation" endpoint; `409` latency; JWT permission codes (seed migration file not yet written); data-reset mechanism; script location; repeat count; adversarial paid variant and retry cap | §2–§4 |
