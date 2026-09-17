# Payment Module — Technical Design

This file is the physical-level completion of `proposal.md` §11.3 (Resilience to Payment Gateway Failures), `docs/requirement.md` §1.3.1/§2.7/§2.8/§2.10, and `architecture.md` §1.4 (Resilience Strategy). Those sources establish *that* the Payment Gateway sits behind a circuit-breaker boundary, *that* a scheduled sweep resolves stuck-`PENDING` transactions, and *that* every payment request is idempotent under client retry — but stop short of concrete Resilience4j parameters, fallback wiring, the reconciliation sweep's own consumer logic, and — nowhere in the prior corpus — a design for the gateway's webhook callback itself. Nothing here overrides `architecture.md`/`proposal.md` — where they disagree, those documents are the authority and this file has a bug.

**Read this file top to bottom to understand the Payment module end to end — payment is this system's second-highest-risk correctness path after seat reservation.** Where it uses a shared platform mechanism (RabbitMQ, the idempotency-key table), it explains what that mechanism gives Payment specifically and links out for the mechanism's own full design.

**Scope:** the `payments` table this module owns, narrated at the rationale level (§1); Resilience4j `CircuitBreaker`/`Retry`/`TimeLimiter` configuration around every Payment Gateway call, and its fallback behavior on both the synchronous charge path and the asynchronous reconciliation-sweep path (§2–§6); the payment webhook endpoint — signature verification, payload handling, and its race with the reconciliation sweep (§7, previously unspecified anywhere in this corpus); idempotent payment-charge under client retry (§8); a consolidated failure-mode table and reference table (§9–§10).

**Out of scope, and where it actually lives:** the exact `payments` column list (`../platform/database-schema.md` §5 — referenced throughout, not repeated here); the RabbitMQ broker/exchange/retry mechanism, reused here only for the reconciliation task (`../platform/messaging.md`); the `idempotency_keys` table's own schema and generic same-transaction pattern (`../platform/database-schema.md` §8.1, `architecture.md` §4.5 — this file applies that pattern to payment-charge, it doesn't redefine it); the seat-hold lifecycle that a payment is racing against (`../modules/registration.md` — this module never extends or shortens that window); real Stripe production settlement (`proposal.md` §5.2 — sandbox mode only); JWT/auth mechanics (`../platform/auth-and-identity.md`, already written).

---

## Table of Contents

- [Architecture Decision Records (ADRs)](#architecture-decision-records-adrs)
  - [ADR Mini: Circuit Breaker Scope for Payment Gateway](#adr-mini-circuit-breaker-scope-for-payment-gateway)
  - [ADR Mini: Payment Webhook Endpoint Design](#adr-mini-payment-webhook-endpoint-design)
- [1. Data Model — What This Module Owns](#1-data-model-what-this-module-owns)
- [2. Resilience4j Configuration Model](#2-resilience4j-configuration-model)
- [3. Sync Charge Path — Decorators & Fallback](#3-sync-charge-path-decorators-fallback)
- [4. Recovery Detection (Shared `HALF_OPEN`)](#4-recovery-detection-shared-half_open)
- [5. Async Reconciliation Sweep — Decorators & Fallback](#5-async-reconciliation-sweep-decorators-fallback)
- [6. Payment Reconciliation Task](#6-payment-reconciliation-task)
- [7. Payment Webhook Endpoint](#7-payment-webhook-endpoint)
- [8. Idempotent Payment-Charge](#8-idempotent-payment-charge)
- [9. Fault Isolation Boundary & Failure Modes](#9-fault-isolation-boundary-failure-modes)
- [10. Reference Table](#10-reference-table)
- [API Surface](#api-surface)

---

## Architecture Decision Records (ADRs)

Reached through the `spec-driven-development` gated workflow: 2–4 alternatives were explored with a trade-off matrix, the human made the call at the Phase 3 Decision Gate, and the reasoning was captured here verbatim rather than left to live only in `SPEC.md` (a working document for the next task, not a durable record).

### ADR Mini: Circuit Breaker Scope for Payment Gateway

**Context:** `docs/requirement.md` §2.7 and `proposal.md` §11.3 require "the system" (singular) to detect Payment Gateway degradation and temporarily stop sending it requests, while unrelated functionality continues normally, with automatic periodic recovery checks. Three independent call-sites talk to the Payment Gateway: the synchronous charge-at-checkout path, the asynchronous payment-reconciliation sweep (§6) that polls the gateway's status API for payments stuck `PENDING` past 15 minutes, and — as of this document — the webhook endpoint's own signature-verification dependency on the gateway's published keys (§7, a lighter-weight dependency than the other two, but still worth naming explicitly). Without shared circuit-breaker state across the two *call-initiating* sites, one call-site could keep hammering a gateway the other has already detected as failing.

**Decision:** A single Resilience4j `CircuitBreaker` instance (registered under one name, `paymentGateway`) guards both the sync charge path and the async reconciliation sweep. `TimeLimiter` and `Retry` are configured independently per call-site: the synchronous charge path uses a short `TimeLimiter` (3s) with no retry, to fail fast and free the request thread for at-checkout UX; the asynchronous reconciliation sweep uses a longer `TimeLimiter` (10s) with retry (3 attempts, exponential backoff), since it runs off the critical path and isn't user-facing. The webhook endpoint (§7) does not participate in this breaker — it never initiates a call to the gateway, only receives one; there is nothing for a circuit breaker to guard there.

**Consequences:**
- Both outbound call-sites observe and contribute to the same `CLOSED`/`OPEN`/`HALF_OPEN` state — when the sync path trips the breaker, the reconciliation sweep also stops calling the gateway on its next tick, instead of continuing to poll a known-down dependency every 15 minutes.
- Two decorator configurations must stay conceptually distinct (fast-fail vs. backoff-retry) despite sharing breaker state — §3 makes this explicit so an implementer doesn't reuse one `TimeLimiter`/`Retry` config for both.
- Recovery detection (`HALF_OPEN` probe) is shared too: whichever call-site's probe call succeeds first flips the breaker back to `CLOSED` for both.

**Trade-offs accepted (user's own reasoning):** an unprotected reconciliation sweep was rejected because it would let the sweep keep polling the Gateway's status API every 15 minutes even after the synchronous path has already detected and reacted to an outage — contradicting §2.7's "temporarily stop calling that service" applied to "the system" as a whole, not just its user-facing half. Two independent `CircuitBreaker` instances per call-site were rejected because they could disagree about whether the gateway is currently healthy, letting one side hammer a gateway the other side has already given up on.

### ADR Mini: Payment Webhook Endpoint Design

**Context:** every other document in this corpus that mentions a "webhook" does so only in passing — `../platform/database-schema.md` §5.1 notes that `payments.resolved_at` is "set by whichever of {webhook, reconciliation sweep} gets there first," and `proposal.md` §16's risk table treats the reconciliation sweep, not the webhook, as "the authoritative source of truth for payment status" specifically *because* "payment gateway sandbox behavior may not fully mirror production timing." Nowhere does any document specify the webhook endpoint itself — its route, its signature verification, its payload handling, or how it's kept safe under the gateway's own at-least-once redelivery behavior (a real Stripe behavior, not a UniHub design choice). Without this, an implementer has no design to build the *faster* of this module's two resolution paths against — only the slower, 15-minute-late fallback.

**Decision:** a dedicated endpoint, `POST /api/v1/webhooks/payment-gateway`, publicly reachable (behind the Ingress, no JWT auth — the gateway is not a UniHub user and cannot hold a UniHub session), authenticated instead by verifying the gateway's own webhook signature header against a shared webhook-signing secret. The handler re-checks `payments.status` before acting — structurally identical to the reconciliation sweep's own re-check (§6) — so it is safe under both the gateway's own redelivery and a race against the sweep resolving the same row first.

**Consequences:**
- The webhook is a genuine fast path: a payment can resolve in seconds via webhook instead of waiting up to 15 minutes for the reconciliation sweep to notice, in the common case where the gateway's callback arrives promptly.
- The reconciliation sweep (§6) remains the system's guarantee of last resort — `proposal.md` §16's stated reasoning (sandbox timing may not mirror production) is exactly why this design does not treat the webhook as sufficient on its own, only as an accelerant.
- The endpoint's authentication model is deliberately different from every other endpoint in this system (no JWT, §7 explains why) — this is called out explicitly so it isn't mistaken for an oversight.

**Trade-offs accepted:** treating the webhook as the *sole* resolution mechanism (removing the reconciliation sweep) was rejected outright — this directly contradicts `proposal.md` §16's already-recorded risk mitigation, which exists specifically because sandbox webhook delivery cannot be trusted to mirror production timing; the sweep stays as the non-negotiable backstop regardless of how reliable the webhook proves in practice. A JWT-authenticated webhook (requiring the gateway to somehow hold a UniHub-issued token) was rejected as architecturally backwards — the gateway is an external system UniHub has no session relationship with; signature verification against a shared secret is the standard, correct pattern for this direction of trust (`architecture.md` §5.4's "credentials never in source" principle, applied to a shared secret instead of a bearer token).

---

## 1. Data Model — What This Module Owns

**Rationale:** `architecture.md` §6.1 names `Payment` as this module's sole entity, and the only module allowed to call the Payment Gateway. Exact columns are in `../platform/database-schema.md` §5.1; this section narrates why the ones central to this file's later sections look the way they do.

- **`status`** (`PENDING`/`SUCCEEDED`/`FAILED`/`CANCELLED`) — a row is created `PENDING` at charge-initiation (§8) and resolved by whichever of {webhook (§7), reconciliation sweep (§6)} reaches it first.
- **`version`** — not named explicitly in `architecture.md` §6.1's anti-overbooking list, but the same optimistic-concurrency pattern is applied here for a distinct reason: the reconciliation sweep and a late-arriving gateway webhook can race to resolve the same `PENDING` row concurrently. This is a direct, consistent extension of an already-established pattern to a second, structurally identical race — not a new invariant.
- **`gateway_transaction_id`** — `UNIQUE`, set once the gateway responds; this is what both the webhook (§7) and the sweep (§6) use to correlate an external event back to a `payments` row.
- **`initiated_at`/`resolved_at`** — `initiated_at` is what the sweep's ">15 minutes in Pending" check (§6) measures from; `resolved_at` is set when `status` leaves `PENDING`, by whichever path gets there first.

---

## 2. Resilience4j Configuration Model

**Rationale:** `docs/requirement.md` §2.7 requires detecting a degraded gateway and temporarily stopping calls to it, rather than letting failed/slow calls accumulate. Resilience4j's `CircuitBreaker` is the mechanism `CLAUDE.md` names for this; the parameters below turn that mandate into a concrete, tunable state machine rather than a vague "add a circuit breaker."

**Model — one shared instance, `paymentGateway`:**

| Parameter | Value | Why |
|---|---|---|
| Sliding window type | `COUNT_BASED` | Payment attempts don't arrive at a constant rate (they spike with paid-ticket registrations, per `docs/requirement.md` §1.4) — a count-based window judges the gateway's health from the last N actual attempts regardless of how much wall-clock time they span, rather than a time-based window that could sit nearly empty during a lull and overreact to one or two failures. |
| Sliding window size | 20 calls | Large enough that a handful of transient failures (a couple of slow paid-checkout attempts) don't alone trip the breaker, small enough that the breaker reacts within a realistic burst of registration activity rather than needing hundreds of calls to notice a real outage. |
| Failure-rate threshold | 50% | Half the window failing is a clear, unambiguous signal of a genuinely degraded gateway — high enough to avoid tripping on ordinary noise, low enough to react well before every call is failing. |
| Wait-duration-in-open-state | 30s | Matches the sync path's fail-fast posture (§3): short enough that a real recovery is detected quickly, long enough not to re-probe a gateway that just failed a moment ago. |
| Permitted-calls-in-half-open-state | 5 | A small sample before committing back to `CLOSED` — large enough to be a meaningful signal, small enough that a still-unhealthy gateway doesn't get exposed to a full burst of traffic while still probationary. |

**This instance is shared by name, not by call-site.** Both the synchronous charge path (§3) and the asynchronous reconciliation sweep (§5, wrapping the call already described in §6) register against the same `paymentGateway` `CircuitBreakerRegistry` entry — this is what makes the shared-detection guarantee in the ADR above literally true, not just described in prose.

**Decorator composition order:** `CircuitBreaker` around `Retry` around `TimeLimiter` around the actual gateway call — the breaker sees the *final* outcome of a call after its own retries and timeout have already been applied, so one already-retried, already-timed-out logical attempt counts as one data point in the breaker's sliding window, not several. Retrying *inside* the breaker's view (rather than each retry independently tripping toward the threshold) keeps the failure-rate calculation meaningful: a single flaky call that succeeds on its second retry attempt is a success from the breaker's perspective, exactly as it should be.

**Trade-off — the shared window mixes two structurally different call profiles, accepted for the PoC stage:** the sync path (§3) is fast and never retries, so it can contribute up to 20 data points to the window in a matter of seconds during a registration-opening spike. The async path (§5), by contrast, can take up to ~47 seconds for a single logical call to resolve under sustained failure (4 attempts × 10s `TimeLimiter`, plus 1s+2s+4s backoff between them) before that one call contributes even a single data point — because the composition order above means the breaker only sees the outcome *after* all retries and backoff have run their course. Two consequences follow, and neither is fully eliminated by this design: (1) the async path's contribution to detection is meaningfully slower than the sync path's, so "detected within the 20th call" (the Guarantee below) can mean very different wall-clock times depending on which call-site happens to fill the window; (2) call-volume imbalance skews the window's signal — during a paid-registration spike, fast sync-path outcomes can dominate the 20-slot window and dilute a genuinely failing async path's contribution, while during a quiet period between spikes, a handful of slow async failures (the sweep only runs once per payment per 15-minute cycle) can disproportionately drive the failure rate since little else is filling the window at that time. This is accepted as-is for this project's PoC scale (`proposal.md` §5.2) rather than engineered around with a larger window, a time-based window, or per-call-site weighting — those are legitimate future refinements if real load-test data (`docs/requirement.md` §3.2) shows this asymmetry actually causing a problem, not something to pre-optimize against here.

**Guarantee:** a gateway degrading past the 50%-of-last-20-calls threshold is detected and stops receiving *any* new calls — from either call-site — within the time it takes that 20th call to complete, not after some longer, call-site-specific delay. That 20th call's own duration is bounded by whichever call-site produced it (§3's 3s ceiling or §5's ~47s worst case) — see the Trade-off above for what that asymmetry means in practice.

---

## 3. Sync Charge Path — Decorators & Fallback

**Rationale:** the synchronous charge path runs inside a student's checkout request — every second spent here is a second of held request-thread and held UI spinner. `docs/requirement.md` §2.7 requires this path to fail fast with "a clear, friendly message," not to make the student wait out a slow, doomed call.

**Model:**

| Decorator | Value | Why |
|---|---|---|
| `TimeLimiter` | 3s | Long enough for a normal Stripe sandbox charge call, short enough that a stalled call doesn't tie up the checkout request past what a student will tolerate waiting on a spinner. |
| `Retry` | 0 (none) | A charge call is not free to retry blindly — retrying a call that *did* reach the gateway but lost the response on the way back risks a second charge attempt for the same request. Idempotency (`docs/requirement.md` §2.8, §8 below) is what makes retries safe *at all*, but this document does not add a Resilience4j-level retry on top of that; a failed sync attempt simply fails fast and lets the student decide whether to try again. |
| HTTP client `connectTimeout` / `readTimeout` | ≤3s each | `TimeLimiter` alone does not stop the underlying blocking call — it only stops the *caller* from waiting past 3s by completing a wrapping future with a `TimeoutException`; the actual outbound HTTP request to the gateway keeps running on its own thread/socket until it finishes or the HTTP client's own timeout fires. Without a client-level timeout at or below the `TimeLimiter`'s 3s, a stalled call still holds a connection-pool slot and a thread well past the point the checkout request has already received its `503` — quietly defeating the thread-freeing purpose this decorator exists for, right at the exact 12,000-concurrent-user spike (`docs/requirement.md` §1.4) this document is meant to protect. |

**Fallback when the circuit is `OPEN`, or the call times out:**

```mermaid
flowchart TD
    Checkout["Student submits payment<br/>(paid registration checkout)"] --> CB{"paymentGateway<br/>circuit CLOSED?"}
    CB -->|OPEN| Fallback["Fallback: no gateway call attempted"]
    CB -->|CLOSED, call proceeds| Call["Call Payment Gateway<br/>(3s TimeLimiter, no retry)"]
    Call -->|Times out or fails| Fallback
    Call -->|Succeeds| Normal["Normal payment flow<br/>(../platform/database-schema.md §5.1)"]

    Fallback --> NoRow["No payments row created —<br/>the gateway was never reached"]
    Fallback --> Response["503 Service Unavailable:<br/>'Payment gateway is temporarily unavailable.<br/>Your seat is held for 15 minutes,<br/>please try again shortly.'"]
    Fallback --> HoldUntouched["registrations.status stays RESERVED,<br/>hold_expires_at unchanged<br/>(../modules/registration.md §1)"]

    classDef flowStyle fill:#E8F5E9,stroke:#2E7D32,color:#1B5E20
    classDef checkStyle fill:#FFF3E0,stroke:#E65100,color:#E65100
    classDef fallbackStyle fill:#FDEDEC,stroke:#B03A2E,color:#78281F
    classDef normalStyle fill:#E3F2FD,stroke:#1565C0,color:#0D47A1

    class Checkout,Call flowStyle
    class CB checkStyle
    class Fallback,NoRow,Response,HoldUntouched fallbackStyle
    class Normal normalStyle
```

No `payments` row is created for a fallback response — a row only ever exists for an attempt that actually reached the gateway (`../platform/database-schema.md` §5.1's `gateway_transaction_id`/`status` columns describe a real attempt, not a rejected-before-dialing one). The seat-hold created by `registrations.status = 'RESERVED'` (`../modules/registration.md` §1) is left completely untouched: the student still has the remainder of their original 15-minute hold window (`docs/requirement.md` §1.3.1) to retry once the gateway recovers, exactly as if this attempt had never happened.

**Trade-off — a student can lose their hold window waiting out an outage:** if the Payment Gateway stays degraded for longer than the time remaining on a student's 15-minute hold, the hold still expires on schedule (`../modules/registration.md` §3's existing expiry sweep, unaffected by this document) and the seat releases. This document does not extend the hold window in response to a gateway outage — doing so would mean the Payment Gateway's *own* availability could indirectly control anti-overbooking timing, a coupling `architecture.md` §6.1's layered anti-overbooking design does not call for and this document does not introduce.

**Guarantee:** a student attempting to pay during a gateway outage gets an immediate, clear answer and keeps their existing hold — they are never left waiting on a call that was never going to succeed, and never silently double-charged by an automatic retry they didn't ask for. Combined with the matching HTTP client timeout above, the request-handling thread and its socket are both released within 3s of a stalled call, not just the caller's logical wait.

---

## 4. Recovery Detection (Shared `HALF_OPEN`)

**Rationale:** `docs/requirement.md` §2.7 and `proposal.md` §11.3 both require the system to "automatically attempt reconnecting... after a period of time" and "resume normal payment processing automatically" once the gateway recovers — Resilience4j's `HALF_OPEN` state is exactly this mechanism, applied here to the shared instance from §2.

**Model:** after 30 seconds in `OPEN` (§2's wait-duration), the `paymentGateway` breaker moves to `HALF_OPEN` and permits up to 5 calls through — from *either* call-site, whichever happens to call next. If enough of those probe calls succeed (per the same 50%-of-window logic), the breaker returns to `CLOSED` for both call-sites simultaneously; if they fail, it reopens for another 30-second wait.

**Guarantee:** recovery is detected and acted on identically regardless of whether the sync charge path or the async reconciliation sweep happens to make the probing call — there is no scenario where one call-site "knows" the gateway is back while the other is still refusing to call it.

---

## 5. Async Reconciliation Sweep — Decorators & Fallback

**Rationale:** the reconciliation sweep (§6) already tolerates redelivery and lost cycles by design — it re-checks `payments.status` before acting and simply matches the same `PENDING` row again on the next 15-minute tick if a cycle is skipped. Wrapping its gateway call in the shared `paymentGateway` breaker adds fault isolation *on top of* that existing tolerance; it does not change the sweep's own correctness logic.

**Model:**

| Decorator | Value | Why |
|---|---|---|
| `TimeLimiter` | 10s | This call runs off the critical path (a background consumer, not a held HTTP request) — there's no user-facing latency budget to protect, so it can afford to wait longer for a gateway response than the sync path can. |
| `Retry` | 3 attempts, exponential backoff (1s, 2s, 4s) | Safe here in a way it isn't on the sync path (§3): this call is a pure status *read* (`GET` transaction status), not a charge attempt — retrying it can never create a duplicate charge, only a duplicate read, which is harmless. |

**Fallback when the shared circuit is `OPEN`:** the reconciliation consumer's call to the gateway is skipped entirely for that message — the same "skip this cycle, the next tick retries" pattern `../platform/distributed-coordination.md` §2 already documents for scheduled-job coordination, applied here to a single payment's reconciliation attempt rather than an entire job run. The payment stays `PENDING`; since it still matches the sweep's `WHERE status = 'PENDING' AND initiated_at < now() - interval '15 minutes'` predicate (§6), it is picked up again on the sweep's next cycle, and the call is retried against the gateway's *then-current* circuit state.

**This does not weaken §6's guarantees.** That section already establishes that a redelivered or repeated reconciliation attempt for an already-resolved payment is a safe no-op (`status <> 'PENDING': COMMIT, do nothing`). A circuit-breaker-induced skip is indistinguishable, from that logic's point of view, from any other reason a cycle's message might not get processed in time — the existing idempotent re-check is what makes this fallback safe, not a new mechanism this document adds.

**Guarantee:** a Payment Gateway outage never causes the reconciliation sweep to pile up failed calls against a dependency it already knows is down; every stuck `PENDING` payment is still eventually resolved once the gateway recovers, on the same eventual timeline §6 already guarantees — this section only changes *how quickly the sweep stops making pointless calls*, not *whether a payment eventually resolves*.

---

## 6. Payment Reconciliation Task

**Rationale:** `docs/requirement.md` §2.10 requires a scheduled sweep to find payments stuck `PENDING` past 15 minutes and resolve them against the gateway. The sweep's *query* (fast, DB-only) and its *resolution* (slow, external gateway call per row) are deliberately decoupled — the same reasoning `../modules/workshop.md`'s summarization job and upload-confirmation flow already apply. This uses the shared RabbitMQ deployment described in `../platform/messaging.md` §1–§2; this section is the Payment-specific message flow and consumer.

**Model:** the reconciliation sweep — coordinated by `unihub:lock:job:payment-reconciliation` so only one pod runs it per cycle (`../platform/distributed-coordination.md` §2) — queries `payments WHERE status = 'PENDING' AND initiated_at < now() - interval '15 minutes'` and publishes one `unihub.payment.reconciliation.requested` message per row found → `unihub.reconciliation.queue` (`../platform/messaging.md` §2). The consumer calls the Payment Gateway's status API for that payment's `gateway_transaction_id` and resolves it:
```
SELECT status, version FROM payments WHERE id = ? (Master)
if status <> 'PENDING': COMMIT, do nothing   -- already resolved, e.g. by the webhook (§7)
                                                  racing this consumer — exactly the race
                                                  payments.version exists for (§1 above)
UPDATE payments SET status = :resolved, resolved_at = now(), version = version + 1
  WHERE id = ? AND version = :expected
```

**Idempotency:** naturally safe under redelivery for the same reason as the seat-hold consumer (`../modules/registration.md` §3) — re-checking `status` before acting means a redelivered or duplicated reconciliation task for an already-resolved payment is a no-op.

**Note — redundant gateway calls across sweep cycles, not a correctness issue:** a payment still `PENDING` past 15 minutes matches the sweep's `WHERE` clause again on every subsequent cycle until it actually resolves, so if a sweep tick fires before a previous cycle's message for the same payment has been processed, a second (redundant) gateway status-check call gets queued for that same payment. This costs an extra external API call, not correctness — the `status <> 'PENDING'` re-check above means the second consumer to run for the same payment always finds it already resolved and no-ops.

**Guarantees:** every payment that goes stale in `PENDING` is eventually resolved; a race between this consumer and the gateway webhook (§7) always converges to one final status, never a lost or double update (§1's `version` column). Retry/dead-lettering on consumer failure follows the generic mechanism in `../platform/messaging.md` §4, applied to `unihub.reconciliation.queue`.

---

## 7. Payment Webhook Endpoint

**Rationale:** per the Payment Webhook Endpoint Design ADR above, this section is the design that was missing everywhere else in the corpus — the *faster* of this module's two resolution paths, complementing (never replacing) §6's 15-minute-late sweep.

**Model — endpoint & authentication:**

| Decision | Value | Why |
|---|---|---|
| Route | `POST /api/v1/webhooks/payment-gateway` | A dedicated route, not folded into any authenticated payment resource path — this endpoint is called by the gateway, never by a UniHub client, so it has no business sharing a URL space with client-facing payment endpoints. |
| Authentication | Gateway webhook signature verification (e.g. Stripe's `Stripe-Signature` header, HMAC over the raw request body against a shared webhook-signing secret) — **no JWT, no `hasAuthority` check** | The gateway is not a UniHub user and holds no UniHub session; `../platform/auth-and-identity.md`'s entire model (resolved permissions, `hasAuthority`) assumes an authenticated *user*, which does not apply here. Signature verification is the standard mechanism for this direction of trust — it proves the request genuinely originated from the gateway, using a secret held only by the gateway and UniHub, injected at runtime per `architecture.md` §5.4 like every other credential in this system. |
| Body handling | The **raw, unparsed** request body is what the signature is verified against, before any JSON deserialization | Signature schemes (Stripe's included) sign the exact bytes sent — verifying against a re-serialized version of a parsed object risks a whitespace/key-ordering mismatch that fails verification for a legitimate request. The raw body must be captured before any framework-level body-parsing middleware transforms it. |
| Rate limiting | **Exempt from `../platform/distributed-coordination.md` §3's per-user/per-IP limiters** | Those limiters key on `userId`/`ipAddress` for abuse prevention against UniHub's own clients; the gateway is a known, trusted, high-volume caller by design, not a traffic-abuse risk this system needs to throttle. |

**Model — payload handling & idempotency:**

```
1. Verify signature against raw body (above). Invalid signature → 400, no further processing,
   nothing read from the body as trusted data.
2. Parse payload; extract gateway_transaction_id and the gateway's reported outcome.
3. SELECT status, version FROM payments WHERE gateway_transaction_id = ? (Master)
   if no row found: 200 OK, log and discard — a webhook for a transaction ID this system
                     never initiated is not an error UniHub needs to act on (could be a
                     gateway test ping, or a delivery misrouted by the gateway itself).
   if status <> 'PENDING': 200 OK, COMMIT, do nothing  -- already resolved, e.g. by the
                     reconciliation sweep (§6) racing this webhook, or the gateway's own
                     at-least-once redelivery of a webhook this system already processed —
                     both collapse to the identical safe no-op.
   if status = 'PENDING':
     UPDATE payments SET status = :resolved, resolved_at = now(), version = version + 1
       WHERE gateway_transaction_id = ? AND version = :expected
4. Always respond 200 OK once the payload has been durably handled (steps 2–3 completed
   without an exception) — a non-2xx response tells the gateway to retry delivery, which
   this system does not need once the row is resolved or determined not to apply.
```

**Why this re-check is structurally identical to §6's:** both the webhook and the reconciliation sweep resolve a `PENDING` row by the same pattern — read current `status`, no-op if already resolved, `UPDATE ... WHERE version = :expected` if not. Neither path trusts that it's the only one that could be acting on a given row at that moment; §1's `version` column is what makes whichever one commits second lose cleanly, rather than corrupt the row or double-notify.

**Guarantee under gateway redelivery:** payment gateways are commonly at-least-once on webhook delivery (a real external-system behavior, not a UniHub design choice) — a webhook for an already-resolved `gateway_transaction_id` is a safe no-op here, the same guarantee `architecture.md` §4.3 requires of every RabbitMQ consumer in this system, applied to an inbound HTTP callback instead of a queue message.

```mermaid
flowchart TD
    Gateway["Payment Gateway"] -->|"POST /webhooks/payment-gateway\n+ signature header"| Verify{"Signature valid\nagainst raw body?"}
    Verify -->|No| Reject400["400 — reject,\nnothing trusted from body"]
    Verify -->|Yes| Lookup["SELECT status, version FROM payments\nWHERE gateway_transaction_id = ?"]
    Lookup -->|Not found| Discard["200 OK — log, discard"]
    Lookup -->|"status <> PENDING"| NoOp["200 OK — already resolved\n(sweep §6 or prior webhook), no-op"]
    Lookup -->|"status = PENDING"| Resolve["UPDATE payments SET status = :resolved,\nresolved_at = now(), version = version + 1\nWHERE ... AND version = :expected"]
    Resolve --> Ack["200 OK"]

    classDef gwStyle fill:#FFFDE7,stroke:#F9A825,color:#795548
    classDef checkStyle fill:#FFF3E0,stroke:#E65100,color:#E65100
    classDef rejectStyle fill:#FDEDEC,stroke:#B03A2E,color:#78281F
    classDef okStyle fill:#E8F5E9,stroke:#2E7D32,color:#1B5E20

    class Gateway gwStyle
    class Verify,Lookup checkStyle
    class Reject400 rejectStyle
    class Discard,NoOp,Resolve,Ack okStyle
```

**Guarantees:** a resolvable payment settles as fast as the gateway's own webhook delivery, typically well inside the 15-minute reconciliation window — without weakening §6's guarantee that every `PENDING` payment resolves eventually regardless of whether any webhook ever arrives at all. A race between the webhook and the sweep always converges to exactly one final status, never a lost or double update.

---

## 8. Idempotent Payment-Charge

**Rationale:** `docs/requirement.md` §2.8/§1.5 require that a client-generated key makes a payment-charge request safe to retry — a student must never be charged twice, nor have two `payments` rows created, even from a repeated click caused by network lag. The generic mechanism is `../platform/database-schema.md` §8.1's design; this section is Payment's application of it, and it is the concrete backstop `../platform/database-schema.md` §5.1's `registration_id UNIQUE` constraint already implies at the schema level.

**Model:** every `POST /api/v1/payments/charge` request carries a client-generated `Idempotency-Key` header. Inside the same transaction as the gateway-call decision (§3):

```
1. SELECT response_status, response_body FROM idempotency_keys
     WHERE scope = 'PAYMENT_CHARGE' AND idempotency_key = :key (Master)
   if found: return the stored response verbatim — do not call the Payment Gateway again,
             not even to "double-check."
2. if not found: proceed with §3's sync charge-path decision (or its OPEN-circuit fallback).
   On any terminal outcome — gateway success, gateway decline, or the circuit-open fallback
   response — INSERT INTO idempotency_keys (scope, idempotency_key, response_status,
   response_body) VALUES ('PAYMENT_CHARGE', :key, :status, :body) — in the SAME transaction
   as the payments row write (or, for the circuit-open case, in the same transaction as
   the decision to write no row at all).
```

**This is the reason §3's `Retry: 0` decision is safe, not just cautious:** Resilience4j-level retry is deliberately absent from the sync charge path specifically because *this* mechanism — not a lower-level HTTP retry — is what makes a client's own retry safe. A student's second click, carrying the identical `Idempotency-Key` their client already generated for the first attempt, replays the first attempt's recorded outcome instead of dialing the gateway a second time.

**Guarantees:** a given `Idempotency-Key` within the `PAYMENT_CHARGE` scope reaches the Payment Gateway at most once — every subsequent request bearing it returns the original result, regardless of which Backend API pod handles the retry (`../platform/database-schema.md` §8.1's UNIQUE constraint on `(scope, idempotency_key)` is the enforced backstop). Idempotency records are retained only long enough to cover a realistic retry window, swept by the scheduled job in `../platform/distributed-coordination.md` §2.

---

## 9. Fault Isolation Boundary & Failure Modes

**Fault isolation (`architecture.md` §1.4):** with the `paymentGateway` circuit `OPEN`, browsing the workshop catalog, free-ticket registration, check-in, and reporting are entirely unaffected — none of those paths call the Payment Gateway or share any component with it beyond the Backend API deployable itself. Only the paid-checkout submission path (§3) and the reconciliation sweep's gateway call (§5) observe the outage; every other module continues exactly as documented elsewhere. The webhook endpoint (§7) is likewise unaffected by the breaker being `OPEN` — it never initiates a gateway call, so there is nothing for the breaker to block on that path.

| Situation | Behavior | Why |
|---|---|---|
| `paymentGateway` circuit `OPEN`, student attempting to pay | `503`, no `payments` row created, seat-hold untouched (§3) | Fail fast, never silently retry a charge, never lose the student's existing hold. |
| `paymentGateway` circuit `OPEN`, reconciliation sweep tick fires | That payment's gateway call is skipped for this cycle; retried on the next 15-minute tick (§5) | Consistent with §6's existing "already-resolved payments are a no-op" idempotency — a skip is just another reason a cycle doesn't get to a row. |
| `paymentGateway` recovers | `HALF_OPEN` probe (either call-site) succeeds, breaker returns to `CLOSED` for both (§4) | `docs/requirement.md` §2.7's "automatically attempt reconnecting... resume normal payment processing." |
| Webhook arrives for a `gateway_transaction_id` UniHub never initiated | `200 OK`, logged and discarded, no `payments` row touched (§7) | Not every webhook delivery necessarily maps to a UniHub-initiated charge (gateway test pings, misrouted delivery) — this is not an error condition. |
| Webhook and reconciliation sweep race to resolve the same `PENDING` row | Whichever commits first wins; the second sees `status <> 'PENDING'` and no-ops (§1, §6, §7) | `payments.version` is the same optimistic-concurrency pattern used everywhere else in this system for exactly this class of race. |
| Retried `POST /payments/charge` with a previously-used `Idempotency-Key` | Original result returned verbatim, gateway never called a second time (§8) | — |

---

## 10. Reference Table

Every algorithm, threshold, and endpoint decision in this document, in one place — the same purpose the closing tables in `../platform/database-schema.md` §11, `../platform/distributed-coordination.md` §5, and `../platform/messaging.md` §5 serve for their own documents.

| Item | Value | Backs |
|---|---|---|
| CircuitBreaker instance | `paymentGateway`, shared across sync + async call-sites | Unified gateway-health detection (`docs/requirement.md` §2.7, `proposal.md` §11.3) |
| Sliding window | `COUNT_BASED`, size 20 | Judges health from actual attempts, not wall-clock time, under bursty paid-registration load |
| Failure-rate threshold | 50% | Clear signal of real degradation without overreacting to noise |
| Wait-duration-in-open-state | 30s | Bounded, fast recovery-check cadence |
| Permitted-calls-in-half-open | 5 | Small, meaningful recovery sample |
| Sync path decorators | `TimeLimiter` 3s, `Retry` 0, HTTP client `connectTimeout`/`readTimeout` ≤3s | Fail-fast UX; no blind retry of a charge attempt; client-level timeout ensures the thread/socket is actually released |
| Async path decorators | `TimeLimiter` 10s, `Retry` 3 (backoff 1s/2s/4s) | Safe to retry (status read, not a charge); no user-facing latency budget |
| Sync fallback | `503`, no `payments` row, seat-hold untouched | `docs/requirement.md` §2.7's "clear, friendly message"; anti-overbooking timing stays independent of gateway health |
| Async fallback | Skip this payment's call for the cycle; retried next tick | Consistent with §6's existing idempotent redelivery |
| Reconciliation sweep coordination | `unihub:lock:job:payment-reconciliation`, 5min lease | Exactly-one-instance-per-cycle (`../platform/distributed-coordination.md` §2) |
| Reconciliation trigger | `payments WHERE status = 'PENDING' AND initiated_at < now() - interval '15 minutes'` | `docs/requirement.md` §2.10 |
| Webhook route | `POST /api/v1/webhooks/payment-gateway` | Fast-path payment resolution (§7) |
| Webhook auth | Gateway signature verification against raw body + shared secret — no JWT | Correct trust direction for an external-system caller, not a UniHub user |
| Webhook idempotency | Re-check `status <> 'PENDING'` before acting, same pattern as §6 | Safe under gateway at-least-once redelivery and race against the sweep |
| Idempotency scope: registration | `REGISTRATION_CREATE` | Owned by `../modules/registration.md` §4 — listed here only for contrast |
| Idempotency scope: payment | `PAYMENT_CHARGE` | Composite uniqueness with client key (`../platform/database-schema.md` §8.1), scoped to this module's charge endpoint (§8) |

---

## API Surface

The concrete HTTP contract for every endpoint this module exposes is specified in `../../../packages/api-contract/paths/payment.yaml` (schemas in `../../../packages/api-contract/schemas/payment.yaml`), not repeated here — including the webhook's signature-based authentication (§7), the shared-breaker fallback behavior (§2–§5), and the `Idempotency-Key` replay semantics (§8) verbatim. Where the two disagree, this file is the authority and the OpenAPI contract has a bug. `getPayment` in that contract is flagged there as an inferred endpoint — a direct corollary of `chargePayment` always returning `PENDING` (§1), never narrated as its own endpoint here. `chargePayment`'s request body shape (`paymentMethodToken`) is also flagged as inferred, since this file only specifies the `Idempotency-Key` header, never the body. See `../../../packages/api-contract/README.md` for the toolchain.
