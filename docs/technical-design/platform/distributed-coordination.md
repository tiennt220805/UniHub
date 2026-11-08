# Distributed Coordination (Redis) — Platform

This file is the physical-level completion of `architecture.md` §4.2 (Redis Coordination & Caching Topology) and §4.6 (Distributed Scheduled Job Coordination), for the parts of that shared Redis deployment that are genuinely cross-cutting — used by more than one business module. Nothing here overrides `architecture.md` — where this file and that one appear to disagree, `architecture.md` is the authority and this file has a bug.

**Why this lives in `platform/`, not in one module:** Redis backs three jobs in this system — cache-aside reads, distributed locking, and rate limiting — and two of those three are genuinely owned by a single module (the workshop-catalog cache belongs to `../modules/workshop.md`; the seat-reservation lock belongs to `../modules/registration.md`, since it's the centerpiece of that module's anti-overbooking story). This file covers only the mechanism a *module* doc shouldn't have to re-explain every time it uses it: the shared Redis deployment itself, the scheduled-job coordination lock (reused by five different jobs across three modules and the shared platform), and rate limiting (applied at multiple endpoints, owned by no single module). A module doc that uses one of these links back here for the mechanism and states only what's specific to its own use.

**Scope:** the shared Redis deployment's topology (§1); the scheduled-job coordination lock, reused by the nightly student sync, the payment reconciliation sweep, the two technical-pattern-entity cleanup sweeps, and the outbox relay (§2); rate limiting (§3); a consolidated failure-mode table across every Redis-backed subsystem in this system, including the two that live in module docs (§4); and the full key/tuning-parameter reference (§5).

**Out of scope, and where it actually lives:** the workshop-catalog cache-aside design (`../modules/workshop.md`); the seat-reservation distributed lock (`../modules/registration.md`); the Postgres-side optimistic-concurrency check itself (`database-schema.md` §3.1/§4.1 — this file's locks are advisory only and never the final word); JWT claim shape/expiry (`auth-and-identity.md`); each scheduled job's own internal correctness (`../modules/user.md` for the nightly sync, `../modules/payment.md` for reconciliation — this file only owns the lock that decides *which instance* runs a job, not what the job does once running).

All configuration in this document targets Redis via the Redisson client (per `CLAUDE.md`'s tech stack).

---

## Table of Contents

- [1. Redis Topology & Deployment](#1-redis-topology-deployment)
- [2. Distributed Lock: Scheduled Job Coordination](#2-distributed-lock-scheduled-job-coordination)
- [3. Rate Limiting](#3-rate-limiting)
- [4. Failure Modes & Fail-Safe Behavior (All Redis-Backed Subsystems)](#4-failure-modes-fail-safe-behavior-all-redis-backed-subsystems)
- [5. Key Format & Tuning-Parameter Reference Table](#5-key-format-tuning-parameter-reference-table)

---

## 1. Redis Topology & Deployment

**Rationale:** `architecture.md` §4.2 already established why cache, lock, and rate-limit share one deployment instead of three — all three are cheap, short-lived, and fail safely, so splitting them across separate stateful components would be complexity without a corresponding benefit. This section only adds the physical shape of that one deployment.

**Model:**

| Decision | Value | Why |
|---|---|---|
| Topology | Single Redis instance (Kubernetes `Deployment`, one pod — it holds nothing durable, see Persistence below; corrected 2026-11-05 from `StatefulSet`, Phase 7 checklist C52) | `CLAUDE.md` frames this deployment as a local Minikube proof-of-concept, not a cloud production rollout — Sentinel/Cluster HA is a deliberate future revisit, not something this design builds toward now. |
| Client | Redisson, single-server mode (`SingleServerConfig`) | Mandated client per `CLAUDE.md`'s tech stack; single-server mode matches the single-instance topology above — Sentinel/Cluster client modes aren't applicable yet. |
| Connection pool | Redisson defaults (`connectionPoolSize` 64, `connectionMinimumIdleSize` 24) | No load-test evidence yet justifies overriding the client's own defaults; revisit once K6/JMeter load testing (`docs/requirement.md` §3.2) produces a number. |
| Required configuration (Phase 3 checklist D21) | `unihub.redis.address` must be set; without it the application does not start. An address that is set but unreachable still starts the application (lazy initialization) | The seat lock and the job lock have no in-process fallback in production: a fallback would run uncoordinated on more than one pod without anyone noticing (INV-08). An unreachable Redis is a runtime fault with a documented direction for each subsystem (§4), not a configuration error, and Redis stays out of Readiness (`observability.md` §5). |
| In the cluster (Phase 7, `k8s/base/redis-deployment.yaml`) | One pod in `unihub`, sidecar-injected, so the API reaches it over mTLS and, under STRICT, only that way (`check-mtls.sh` finds its port refusing plaintext); requests 50m CPU / 64Mi, limits 250m / 256Mi; liveness and readiness are `redis-cli ping`. Measured on a fresh deploy (2026-11-07): 7–10 MiB resident, idle and after the resilience battery | The limit is far above the footprint because nothing durable lives here; scaling it to 0 is the Redis outage of scenario 2b (`dependency-outage.sh redis`: every API pod stays `Ready`, `dependencies` `DEGRADED`, registration `503`, INV-08). |
| Persistence (RDB/AOF) | Disabled | Nothing stored in this Redis is authoritative data (`database-schema.md` §6.3's cross-referenced retention table: coordination locks and rate limits are seconds-lived; cache is disposable). A restart losing all keys is a non-event — caches refill on next read, locks simply don't exist until next acquired, rate limiters reset to full budget. |

<div align=center>

```mermaid
flowchart TB
    subgraph Pods["Backend API (N parallel pods)"]
        API1["Pod 1"]
        API2["Pod 2"]
        APIN["Pod N"]
    end

    Redis[("Redis\n(single instance, Redisson client)")]

    API1 & API2 & APIN --> Redis

    Redis -->|"cache miss"| Replica[("PostgreSQL Replica")]
    Redis -->|"lock is advisory only —\nfinal accept/reject at"| Master[("PostgreSQL Master\n(version + check constraint)")]

    classDef apiStyle fill:#E8F5E9,stroke:#2E7D32,color:#1B5E20
    classDef redisStyle fill:#FDEDEC,stroke:#B03A2E,color:#78281F
    classDef pgStyle fill:#F3E5F5,stroke:#6A1B9A,color:#4A148C

    class API1,API2,APIN apiStyle
    class Redis redisStyle
    class Replica,Master pgStyle

```

</div>

**Guarantees:** losing the Redis pod entirely degrades the system (slower reads, rejected registration writes, skipped job cycles — see §4) but never corrupts data; every value Redis holds is either a disposable copy or a coordination signal with a bounded lifetime.

---

## 2. Distributed Lock: Scheduled Job Coordination

**Rationale:** `architecture.md` §4.6 requires exactly one pod to execute a given scheduled job per cycle, never zero (barring a Redis outage) and never more than one. **What is enforced and tested is "never two executions at the same time"** (Phase 3 checklist C10): four of the five jobs run on `fixedDelay`, and the lock is released when the body finishes, so another pod may legitimately run the next execution right after — that is the next cycle, not a duplicate. Only the nightly sync is cron-aligned. Triggers are not changed to cron to make "per cycle" literal. This is the same lock primitive `../modules/registration.md` uses for seat reservation, reused here for a structurally different purpose — job exclusivity, not write-path mutual exclusion — so the parameters differ even though the mechanism doesn't.

**Model:** one lock key per job, each with its own lease sized to comfortably exceed that job's expected runtime — a single shared lock/lease across all five jobs would either be too short for the slowest (nightly sync) or needlessly long for the fastest (the cleanup sweeps), and either way would leave a job type name resolving to logic totally unrelated to another's.

| Job | Key | Lease (TTL) | Why this lease |
|---|---|---|---|
| Nightly student sync (`../modules/user.md`) | `unihub:lock:job:nightly-student-sync` | 30 minutes | Runs once daily, processing a full legacy-system export — data volume varies, and this job is never in a tight loop with others waiting, so a generous lease costs nothing. Revisit if real runtime approaches this value. |
| Payment reconciliation sweep (`../modules/payment.md`) | `unihub:lock:job:payment-reconciliation` | 5 minutes | Scans `PENDING` payments older than 15 minutes and calls the gateway's status API per row — bounded by realistic pending-transaction volume, not by anything close to 30 minutes. |
| Idempotency-key cleanup sweep (`database-schema.md` §8.1) | `unihub:lock:job:idempotency-cleanup` | 1 minute | A single bounded `DELETE ... WHERE status = 'COMPLETED' AND completed_at < ...` — fast by construction. Never deletes an `IN_PROGRESS` row: a charge's open key is completed by reconciliation or removed by the breaker compensation, never by age (`database-schema.md` §8.1). |
| Event-publication cleanup sweep (`database-schema.md` §8.2) | `unihub:lock:job:event-publication-cleanup` | 1 minute | Same shape as the idempotency sweep — a bounded delete of already-published rows. |
| Outbox relay (`messaging.md` §5) | `unihub:lock:job:outbox-relay` | 30 seconds | Runs every second, not nightly or every few minutes. One cycle publishes at most 200 rows with per-message confirms, a small fraction of 30s. A short lease also means a crashed relay pod is replaced within 30s, which bounds how long events can stall. |

```java
RLock lock = redisson.getLock("unihub:lock:job:" + jobName);
boolean acquired;
try {
    if (lock.isHeldByCurrentThread()) { // a Redis round-trip, so inside the try
        return; // RLock is reentrant; the JobLock port is not — a holder asking again gets nothing
    }
    acquired = lock.tryLock(0, leaseSeconds, TimeUnit.SECONDS); // non-blocking: acquire now or skip
} catch (RedisException e) {
    return; // any Redis failure, refused or hung (RedisTimeoutException) — skip this cycle (§4.6)
}
if (!acquired) {
    return; // another instance already claimed this cycle — skip, don't queue a retry
}
try {
    // run the job
} finally {
    lock.unlock();
}
```

**Failure handling (Phase 3 checklist D20):** every `org.redisson.client.RedisException` counts as "no lock" — a refused connection and a hung one (which surfaces as `RedisTimeoutException` after the shared client's 0.5 s command timeout) alike; the same rule holds for the seat lock (`../modules/registration.md` §2). Lock ownership in Redisson is the client id plus the thread, so each application instance has its own `RedissonClient`.

`tryLock` wait-time is **0** here, unlike the seat lock's 2 seconds (`../modules/registration.md`) — a scheduled tick isn't a user waiting on a response; there's no reason to block. Either this instance claims the cycle immediately, or it skips and trusts the next tick, exactly as `architecture.md` §4.6 describes.

**Trade-off — the same fixed-lease-expiry risk as the seat lock, without the same second layer:** if a job's actual runtime exceeds its lease (most plausible for nightly student sync, line above: "data volume varies"), the lease expires while the job is still running, and a second pod can then acquire the same lock and start a **concurrent second run** of that job — briefly violating the "exactly one instance per cycle" guarantee this section exists to provide. Unlike the seat lock, there's no PostgreSQL optimistic-concurrency check standing behind this one as a second layer, so this risk is only survivable if the job's own write path is idempotent under a concurrent duplicate run:
- **Outbox relay** is safe by the messaging contract rather than by its predicate: a concurrent second run may publish some rows twice, which is exactly the at-least-once delivery every consumer is already built to tolerate (`messaging.md` §4–§5).
- **Payment reconciliation, idempotency cleanup, event-publication cleanup** are naturally safe: each operates on rows by a `WHERE` predicate (`status = 'PENDING'`, `completed_at < ...` / `created_at < ...`) that a second concurrent pass simply finds nothing new to act on — a duplicate run degrades to a no-op, not corruption.
- **Nightly student sync** is the one job where this isn't automatically true — a concurrent second run against the same legacy CSV import could race on the same upsert rows. This job's lease (30 minutes) is a guess at normal runtime, not a guarantee; treat sustained runtime approaching the lease as an operational alert to shorten the job or lengthen the lease, and require the sync's own upsert logic (`../modules/user.md`) to be safe under a concurrent duplicate run regardless — this file cannot make that job idempotent on its own, it can only flag that the lock alone won't catch an overrun.

**Guarantees:** exactly one instance runs a given job per cycle under normal conditions; a skipped cycle (lost the race, or Redis unreachable) is always recovered by the next scheduled tick, never queued or retried mid-cycle (§4.6).

---

## 3. Rate Limiting

**Rationale:** `docs/requirement.md` §2.5 requires limiting requests per user/device within a time window, applied consistently across all parallel pods — an in-process limiter would leave a gap as wide as the one a local lock would leave for seat reservation. Redisson's `RRateLimiter` is a token-bucket primitive native to the already-mandated client. This applies across endpoints owned by different modules (registration attempts, login), which is why it's a platform concern rather than any one module's.

**Model:**

**Scope — by identity (decided 2026-11-05, Phase 7 checklist D90, on the owner's delegation).** `docs/requirement.md` §2.5 limits "a given user/device". A per-IP budget on all traffic would throttle students behind one campus NAT and several check-in devices behind one venue NAT, while a per-IP budget on unauthenticated traffic only would leave a signed-in user unlimited on every endpoint but registration. So the general budget is keyed by **who is calling**: the user when the request carries a valid token, the client address otherwise.

| Scope | Key | Rate | Why |
|---|---|---|---|
| Per-user registration attempts (`POST /api/v1/registrations`) | `unihub:ratelimit:registration:{userId}` | 5 requests / 10s | Generous enough for legitimate retry-on-failure UX (a student whose first attempt got a transient 503 from `../modules/registration.md`'s seat lock should be able to retry within seconds), tight enough to block a scripted hammering loop aimed at one workshop's seats. |
| General API traffic, authenticated | `unihub:ratelimit:global-user:{userId}` | 100 requests / 60s | One user, whatever address they come from; users behind a shared NAT do not share a budget. The Web Application's own polling (seat count every 5 s, report every 10 s) stays far below it. |
| General API traffic, unauthenticated — including login and every request whose token is missing, expired or invalid | `unihub:ratelimit:global-ip:{ipAddress}` | 100 requests / 60s | Catches what a per-user limiter cannot see: catalog scraping, credential stuffing against login, and floods carrying a forged token. |
| The payment-gateway webhook (`payment.md` §7) | — | exempt | Its body is signature-verified, and the gateway retries from few addresses — in the cluster, from one stub pod. |

**Where the filters sit.** The general limiter runs after bearer-token authentication (so it knows the user) and before authorization; a request that ends unauthenticated — no token, or one the authentication filter rejects with `401` — is charged to its address **in the authentication entry point**, so an invalid token never skips the budget, and over budget it gets `429` instead of `401`. The registration limiter runs on its one operation. Every operation except the webhook can therefore answer `429` with `Retry-After` (seconds, rounded up) and a problem body carrying `correlationId`; the contract declares it on all of them, changed together with the code (Phase 7 task 3.2). A switch, `unihub.rate-limit.enabled`, is on by default and on the cluster, and off in the shared integration-test suite (every test runs from one address), on only in the limiter's own tests.

**The client address (corrected 2026-11-05 before any code, Phase 7 checklist D91).** Behind the Istio ingress gateway (`deployment-and-cicd.md` §2) the application takes the client address from **one value the edge gateway sets and overwrites** — `X-Envoy-External-Address`, which Istio's ingress gateway (Envoy, `use_remote_address`) writes from the peer it saw — read only when the request reached the container through its own sidecar (peer `127.0.0.6`, Istio's inbound passthrough address); otherwise (no gateway in front: docker-compose, integration tests) the socket's remote address. No `RemoteIpValve` and no walk through `X-Forwarded-For`: under the sidecar every connection comes from `127.0.0.6`, and the valve's private-range walk would continue into client-written entries (the address appended through `minikube tunnel` is itself private). `numTrustedProxies` stays 0, the default, since nothing stands in front of the gateway on Minikube. Whether 1.31 keeps that header across the sidecar hop and overwrites a client-sent value was checked at Phase 7 task 8.3 (the result is below); had it not, the owner would have decided (stop condition) — the fallback is not a header walk. Rotating `X-Forwarded-For`, or sending one's own `X-Envoy-External-Address`, therefore never resets a budget (`security-testing.md` S-16). **Found by the cluster run (Phase 7 task 8.3, 2026-11-06):** the gateway does keep the header across the sidecar hop and overwrites a client-sent value (a forged `203.0.113.77` arrived as the gateway's own view of the peer), so the stop condition was not met — but the claim above was false on the cluster until `server.forward-headers-strategy: none` was set. Without it Spring Boot detects Kubernetes and turns the strategy to `native` on its own, Tomcat's `RemoteIpValve` replaces the remote address with the last non-private entry of the client-written `X-Forwarded-For` before `ClientAddress` runs, and every rotated value had a budget of its own (130 unauthenticated requests, all `200`; the Redis keys were `global-ip:198.51.100.N`). `RateLimitIT` could not see it (MockMvc has no Tomcat, so no valve, and no cloud platform); `RateLimitForwardedHeadersIT` starts the real server with `spring.main.cloud-platform=kubernetes` and fails without the setting.

```java
// Redisson 3.45.1. Each key is per identity, so its rate is set the first time it is used, with a
// keep-alive so an idle key expires instead of accumulating (one per address ever seen). When the key
// has expired, tryAcquire's script fails with "RateLimiter is not initialized" — so there is no
// pod-local "already configured" cache (it would turn into errors once a key expires): on that
// error, set the rate and try once more.
RRateLimiter limiter = redisson.getRateLimiter("unihub:ratelimit:registration:" + userId);
boolean acquired;
try {
    acquired = limiter.tryAcquire();
} catch (RedisException notInitialized) {          // checked at task 3.1: RedisException, message "RateLimiter is not initialized"
    limiter.trySetRate(RateType.OVERALL, 5, Duration.ofSeconds(10), Duration.ofSeconds(20));
    acquired = limiter.tryAcquire();
}
if (!acquired) {
    return HttpStatus.TOO_MANY_REQUESTS; // 429, Retry-After = the window
}
```

**As built (Phase 7 tasks 3.1–3.5, 2026-11-05)** — `RedissonRateLimiter` behind the `RateLimiter` port, `ConsumeRateLimitBudget` use case, `RateLimitGate` and two filters in `shared.infrastructure.security`:

- **The expired-key failure, measured** (`RedissonRateLimiterIT`, Redisson 3.45.1): a key that was never set or has expired makes `tryAcquire()` throw `org.redisson.client.RedisException` whose message contains `RateLimiter is not initialized`; only that exception gets `trySetRate` and one more try. Any other `RedisException` (a refused connection, a `RedisTimeoutException`) is an outage: no second attempt, so a paused Redis costs one command timeout (0.5 s), not two. An expired key restarts with a clean budget.
- **`RateType.OVERALL`**, never `PER_CLIENT` (that counts per `RedissonClient`, i.e. per pod — two pods would each admit the whole budget); keep-alive is twice the window.
- **`Retry-After` is the window** (60 s or 10 s), an upper bound: Redisson 3.45.1 has no public time until the next permit, and its internal keys are not read. A client that waits the whole value always succeeds.
- **Fail open, quickly.** The client's 0.5 s command timeout is the only timeout; after a failure a pod leaves Redis alone for `unihub.rate-limit.failure-cooldown` (5 s, the catalog cache's own pause) so that an outage does not put 0.5 s on every request. Requests let through during the pause are counted too (`unihub_rate_limit_bypassed_total`, reason of the failure that started it). The pause is per pod on purpose: it only decides when one pod asks again, never a budget.
- **Checked through the gateway on the cluster** (`k8s/scripts/check-rate-limit.sh`, Phase 7 task 8.3, M-62; re-run on a fresh deploy 2026-11-07, three pods): six rapid registrations by one user give `201 409 409 409 409 429` with `Retry-After`, whichever pod serves them (one budget in Redis, not one per pod); 130 unauthenticated requests with a rotating `X-Forwarded-For` and a forged `X-Envoy-External-Address` get exactly 100 `200` then 30 `429`. A caveat of scale: one host machine is one address, so a per-address budget cannot be exercised from many clients here (a Phase 7 sign-off §5 item).
- **The switch** `unihub.rate-limit.enabled` (default true): off, no filter charges anything (and the series of the bypass counter still exist at 0). `contract-diff.sh` and the shared integration-test suite turn it off.
- **Where it sits:** the two filters are added to the API's security chain after `BearerTokenAuthenticationFilter` (they are not `Filter` beans: a servlet `Filter` bean would also run outside the chain, where the token's validity is unknown). A request is charged once: the first charge sets a request attribute and the authentication entry point, which charges the address of a request the bearer filter refused (so a forged token cannot skip the budget), does not charge it again. The management port (8081) has its own chain and is never charged.

Every Redis call of the limiter has a short timeout, so a hung Redis never holds a request (§4).

**Trade-off — this is abuse prevention, not a correctness mechanism:** rate limiting exists to protect system capacity, not to enforce anti-overbooking — that guarantee is entirely `../modules/registration.md`'s and `database-schema.md`'s. A rate-limited request is simply asked to slow down; it carries no bearing on whether a seat was, or wasn't, correctly reserved.

**Guarantees:** a client's request budget is identical regardless of which pod handles the request (§4.2) — never a per-instance-only limit that a distributed client could route around.

---

## 4. Failure Modes & Fail-Safe Behavior (All Redis-Backed Subsystems)

One consolidated view across every Redis-backed subsystem in this system — each fails differently on purpose, not by a single blanket policy. The first two rows live in module docs; they're included here so an operator has one table to check instead of four.

| Subsystem | Redis unreachable → | Rationale |
|---|---|---|
| Workshop-catalog cache-aside (`../modules/workshop.md`) | Fall through to the PostgreSQL Replica directly, uncached. After a failure the cache is left alone for 5s (one failed attempt per 5s, not per request); a `DEL` is still always attempted | The cache is never authoritative (§4.2) — a miss only costs a Replica read, never a correctness problem. Redis client timeouts are 0.5s with no retry (`unihub.redis.*`), so the one failed attempt is cheap. |
| Seat-reservation lock (`../modules/registration.md`) | **Reject** the registration write (`503`, `Retry-After`); a seat-hold expiry waits in its retry chain, never parked (`../modules/registration.md` §3) | §4.2: coordination unavailable means the write must never proceed uncoordinated. Rejecting protects the anti-overbooking invariant when the advisory layer can't do its job — Postgres's check alone, absent the lock's throughput-shaping role, isn't treated as sufficient under the exact high-contention conditions (`docs/requirement.md` §1.4) this lock exists for. |
| Scheduled-job lock (§2) | **Skip** this cycle; the next tick retries | §4.6: uncoordinated execution (two instances running the same job at once) is worse than a delayed one. |
| Rate limiter (§3) | **Fail open** — allow the request through unthrottled, within a short timeout (a hung Redis never holds the request), and **count** every bypass in `unihub_rate_limit_bypassed_total{reason}`, with a Warning alert (`observability.md` §4, §6; Phase 7 checklist D90) — a fail-open path here never fails silently | Not covered explicitly by §4.2. Proposed here because the one correctness-critical defense in this system (the seat lock) is already fail-closed during the same outage — failing the rate limiter open too doesn't reopen the overbooking risk, it only means abuse-prevention is temporarily degraded for the duration of the outage, which is an acceptable secondary-layer trade-off rather than a second correctness gap stacked on top of the first. |

---

## 5. Key Format & Tuning-Parameter Reference Table

Every Redis key pattern and numeric value across this system, in one place, traced back to what it backs — including the two subsystems that are fully specified in their owning module doc. This is the single index; the module docs remain the authoritative source for their own rows.

| Key Pattern | TTL / Lease / Rate | Backs |
|---|---|---|
| `unihub:cache:workshop:detail:{workshopId}` | 300s TTL | Caching slow-changing workshop data (`docs/requirement.md` §2.4) — full design in `../modules/workshop.md` |
| `unihub:cache:workshop:seats:{workshopId}` | 3s TTL | Near-real-time seat availability display (§1.3.1) — full design in `../modules/workshop.md` |
| `unihub:cache:workshop:list:{filterHash}` | 30s TTL | Catalog search/browse caching (§2.4) — full design in `../modules/workshop.md` |
| `unihub:lock:seat:{workshopId}` | 5s lease, 2s wait | Anti-overbooking coordination layer (`architecture.md` §6.1/§4.2, `proposal.md` §11.1) — full design in `../modules/registration.md`; advisory only, `database-schema.md` §3.1/§4.1's `version`/check-constraint pair is the final authority |
| `unihub:lock:job:nightly-student-sync` | 30min lease, 0s wait | Exactly-one-instance nightly sync (§2 above) |
| `unihub:lock:job:payment-reconciliation` | 5min lease, 0s wait | Exactly-one-instance reconciliation sweep (§2 above) |
| `unihub:lock:job:idempotency-cleanup` | 1min lease, 0s wait | Exactly-one-instance idempotency sweep (§2 above) |
| `unihub:lock:job:event-publication-cleanup` | 1min lease, 0s wait | Exactly-one-instance outbox cleanup sweep (§2 above) |
| `unihub:lock:job:outbox-relay` | 30s lease, 0s wait | Exactly-one-instance outbox relay per 1s cycle (§2 above, `messaging.md` §5) |
| `unihub:ratelimit:registration:{userId}` | 5 req / 10s, keep-alive 20s | Abuse prevention on registration attempts (§3 above) |
| `unihub:ratelimit:global-user:{userId}` | 100 req / 60s, keep-alive 120s | General budget of an authenticated user (§3 above) |
| `unihub:ratelimit:global-ip:{ipAddress}` | 100 req / 60s, keep-alive 120s | General budget of unauthenticated traffic, including invalid tokens (§3 above) |
