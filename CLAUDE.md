# UniHub Workshop System

## Project Status

**Design stage — no application source code exists yet.** This repo currently holds only the requirements/design corpus (`docs/`, `blueprint/`). There is no Gradle build, no Spring Boot module, no Dockerfiles, no CI config. Do not assume any of that scaffolding exists — check before referencing a path or command.

- `docs/requirement.md` — the SRS. Source of truth for *what* the system must do.
- `blueprint/proposal.md` — *why* these decisions (business case, scope, tech choices, risks).
- `blueprint/architecture.md` — *how* (C4 diagrams, data/messaging topology, security architecture, database architecture). This is the authoritative design reference — treat it like a spec section per the Context Hierarchy: load only the section relevant to the task at hand, not the whole 900+ line file.
- `blueprint/technical-design/` — physical-level detail (DDL, Redis key formats, TTLs, retry policy) that `architecture.md` explicitly says does *not* belong at the macro level. Organized as `platform/` (cross-cutting mechanisms shared by multiple modules — schema, Redis, RabbitMQ, auth, observability, deployment) and `modules/` (one file per business module — user, workshop, registration, payment, checkin, notification — narrating that module's full story end to end, linking into `platform/` for shared mechanisms rather than re-deriving them). Populated; keep this split when adding new physical-level detail rather than reverting to one file per infra concern.

When asked to implement a feature, first find the relevant section in `architecture.md`/`proposal.md` and treat its "Guarantees" as non-negotiable acceptance criteria — the "Trade-offs" subsections tell you which limitations are deliberate and should *not* be "fixed" as a drive-by.

## Target Tech Stack (per `proposal.md` §10, `architecture.md` §1.2)

- **Backend:** Java 21, Spring Boot 3.x — one deployable, stateless **Modular Monolith** (not microservices).
- **Data:** PostgreSQL (single Master, read Replicas), read/write splitting at the datasource layer.
- **Coordination/Cache:** Redis via Redisson (distributed locks, rate limiting, cache-aside reads).
- **Messaging:** RabbitMQ (AMQP) — same deployable consumes its own queues; there is no separate worker service.
- **Object storage:** AWS S3 — brochure PDFs only; the one managed-cloud dependency in an otherwise local/self-hosted stack.
- **Orchestration:** Kubernetes via Minikube (local proof-of-concept, not a cloud production rollout).
- **Service mesh:** Istio + Envoy sidecars — mTLS on every pod-to-pod hop, Zero-Trust.
- **Resilience:** Resilience4j circuit breaker around the Payment Gateway (Stripe, sandbox mode only).
- **Observability:** Prometheus + Grafana (metrics), OpenTelemetry (tracing).
- **Testing:** JUnit + Testcontainers — integration tests run against real Postgres/Redis/RabbitMQ, never in-memory substitutes, because the behaviors under test (locking, transaction isolation) are exactly what an in-memory fake wouldn't reproduce faithfully.

### Frontend Web (Student full journey: discovery/registration/payment/e-ticket + Organizer/Admin Dashboard)

Covers the Student's entire journey end-to-end, including viewing/downloading the issued QR e-ticket — the Student never touches the Mobile App below.

- **Core:** React + TypeScript, built with Vite.
- **State & async data:** TanStack Query (server state) + Zustand (client state).
- **UI & styling:** Tailwind CSS + Shadcn UI.
- **Forms & validation:** React Hook Form + Zod.

### Mobile App — "Check-in Client" (Check-in Staff ONLY — offline QR scanning)

This is the concrete realization of the "Check-in Client" container in `architecture.md` §1.2/§2.2. **Scope boundary: Check-in Staff only.** A Student never installs, authenticates into, or interacts with this app in any form — their e-ticket lives entirely in the Web Application above; this app only ever scans it.

- **Framework:** React Native (Expo) + TypeScript.
- **State & async data:** TanStack Query + Zustand — same stack as the Web Application.
- **Local database (offline-first):** Expo SQLite, managed by Drizzle ORM — the durable queue for scan events; not a plain in-memory queue, since it must survive an app restart or connectivity loss.
- **Camera & scan:** Expo Camera, for QR e-ticket scanning.
- **Security & cryptography:** JOSE performs on-device asymmetric (RSA/ECDSA) signature verification for offline ticket authenticity (`architecture.md` §5.3) — the device only ever holds the public verification key, never a signing key. Expo SecureStore holds that public key and the client's auth token under the device's hardware-backed keystore.

## Module Boundaries (`architecture.md` §1.1, §2.3, §6.1)

Six business modules, each the **sole writer** of its own entities — a module never reaches into another module's tables directly, only through that module's own interface:

| Module | Owns (entities) | Exclusive external dependency |
|---|---|---|
| User | User, Role, Permission | Legacy Student Management System (nightly sync) |
| Workshop | Workshop | Object Storage (S3) + AI Summarization Service |
| Registration | Registration | — (calls Workshop for capacity, Payment for paid tickets, User for identity) |
| Payment | Payment | Payment Gateway (only module allowed to call it) |
| Check-in | Attendance | — |
| Notification | Notification | Notification Providers (only module allowed to call them, consumes events async — never called synchronously) |

Three cross-cutting **Technical Pattern Entities** are not business data — don't model them like domain objects: Idempotency Key, Event Publication (transactional outbox), Audit Event.

## Non-Negotiable Invariants

These are correctness guarantees the architecture was explicitly designed around. Do not "simplify away" any layer below — each one exists because a single layer alone was judged insufficient:

- **Anti-overbooking is layered, not single-mechanism** (`architecture.md` §4.2, §6.1, `proposal.md` §11.1): Redis distributed lock is *advisory only* — the actual accept/reject decision is PostgreSQL's optimistic-concurrency check (version column + check constraint) at the Master. Never treat the Redis lock as sufficient on its own; never remove the DB-level check constraint as "redundant."
- **Idempotency records live in PostgreSQL, in the *same transaction* as the business effect they guard** (§4.5) — never in Redis alone, and never written in a separate transaction from the effect. A unique constraint on the key is the enforced backstop, not just an application-level check.
- **RBAC is permission-based, never role-name-based** (§5.1): every authorization check asks "does this user have permission X," never "is this user's role Y." The JWT carries the *resolved permission list*, not the role name (`docs/requirement.md` §2.11 requires this explicitly).
- **Event publication uses the transactional outbox pattern** (§4.3, §6.1): the business write and the "intent to publish" commit atomically, before the message reaches RabbitMQ. Never publish directly from application code without an outbox row.
- **Schema migrations are explicit and versioned, run once before rollout** (§6.2) — never rely on ORM auto-DDL / entity-driven schema generation at boot. A migration must tolerate being read by both the old and new pod version mid-rollout.
- **Audit logging is fail-open and async** (§5.5): a broken audit pipeline must never block or fail the business operation it would have recorded — but the audit-publish failure itself must surface as an operational alert, not disappear silently.
- **Consumers must be idempotent under redelivery** (§4.3): RabbitMQ is at-least-once, never exactly-once. Re-processing the same message must be a safe no-op.
- **Check-in must work fully offline** (§5.3, §11.4): tickets are self-verifying via signature (no server round-trip, verified on-device via JOSE); scan events queue durably on-device (Expo SQLite/Drizzle) until sync; validity (cancelled? already checked in?) is only resolved at server-side reconciliation, using earliest-timestamp-wins for duplicate scans.
- **Secrets are never in source or committed config** — injected at runtime, regardless of environment.

## Boundaries

- Don't add microservice splits, new modules, or infrastructure components beyond what `architecture.md`/`proposal.md` specify — module boundaries are drawn so a future split is *possible*, not something to do preemptively now.
- Don't relax any guarantee in "Non-Negotiable Invariants" above to fix a bug faster — if a fix seems to require that, stop and flag it; it likely means the bug is elsewhere.
- Don't write physical-level design decisions (schema DDL, Redis key naming, TTL values, retry counts) directly into `architecture.md` — that belongs in `blueprint/technical-design/`, which stays at implementation-detail level.
- Real financial settlement and production cloud deployment are explicitly **out of scope** (`proposal.md` §5.2) — don't build toward them. The Mobile App itself *is* in scope and gets built; only publishing a signed build to the Apple App Store / Google Play Store is out of scope — distribution stays on non-store channels (e.g. Expo preview/internal builds).
- When a task touches a "Known Limit" documented in `architecture.md` §5.6 or a trade-off elsewhere in the doc, treat it as a deliberate, already-discussed decision — surface it to the user rather than silently "fixing" it.
