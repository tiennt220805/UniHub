# Backend Coding Standards — UniHub Guidelines

This is the production-code counterpart to `docs/testing/guidelines/coding-standards.md` (test code only). It governs everything under `src/main/java` and combines three architectural styles that hold **at the same time**:

- **Modular Monolith** (`architecture.md` §1.1, §2.3) — one deployable, six business modules, each the sole writer of its own entities (`architecture.md` §6.1).
- **Clean / Hexagonal Architecture** — inside each module, source dependencies point inward only: `domain` ← `application` ← adapters (`web`, `infrastructure`).
- **Event-Driven Architecture** — cross-module effects that do not need same-transaction consistency travel as messages through the transactional outbox (`architecture.md` §4.3, §6.1).

SOLID is not a separate checklist; §4 maps each letter to the rule that realizes it.

**Precedence.** This file never overrides `architecture.md`, `requirement.md`, the `docs/technical-design/` files, or `packages/api-contract/`. It *cites* names those sources already fixed (table columns, enum values, routing keys, schema names, HTTP paths) and never invents alternatives. If this file and one of them disagree, they are right and this file has a bug. If a rule here needs a change in one of them, the change is listed in §27 and made *there* first.

**Out of scope:** test-code conventions (`docs/testing/guidelines/coding-standards.md`); real-vs-mock decisions (`docs/testing/guidelines/mocking-guidelines.md`); module business rules (`docs/technical-design/modules/*.md`); physical values such as lock leases, TTLs, retry tiers and DDL (`docs/technical-design/`).

---

## Rules at a Glance

Read this first, then only the sections for the layer you are touching. Every rule links to its full explanation. **MUST** and **MUST NOT** are absolute; a task that seems to need an exception means the design is missing something — stop and ask.

**Structure**
1. MUST NOT import another business module's classes except its `com.unihub.<module>.api..` package; `com.unihub.shared..` is importable everywhere (§1).
2. MUST put code in the package its role dictates: `domain` / `application/{port/in,port/out,usecase,command,result,event}` / `api` / `web` / `infrastructure/{in,out,config}` (§2).
3. MUST NOT place cross-cutting code (outbox, relay, idempotency, audit, error handling, routing, `Clock`) in a business module — it lives only in `com.unihub.shared` (§13).
4. MUST keep `ArchitectureTest` green; never weaken a rule in it to make a change pass (§26.2).

**Layers**
5. `domain`: plain Java only, no annotations, never reads a clock — time arrives as an `Instant` parameter (§5, §16).
6. `application`: the only Spring imports allowed are `org.springframework.stereotype..` and `org.springframework.transaction.annotation..` (§3).
7. `web` and `infrastructure/in`: call exactly one `port/in` per request or message; no business logic; never touch a repository, `port/out`, or `infrastructure/out` class (§3, §8.5, §10).
8. `infrastructure/out`: implements exactly one `port/out`; translation only (§9).

**Use cases & transactions**
9. One use case class per operation, named after its port minus `UseCase`, never `*Service` (§6.1, §14.1).
10. `@Transactional` only on use case methods or their `*Transaction`/`*Steps` helper bean; never on controllers, listeners, schedulers or adapters (§19.1).
11. MUST NOT, inside a transaction: call a third-party HTTP API, publish to RabbitMQ, or acquire/wait on a Redis lock (§19.1).
12. Distributed lock → then transaction → commit → then unlock, via the two-bean pattern; never call a `@Transactional` method through `this.` (§19.2, §19.3).
13. A cross-module write that must share the caller's transaction goes through the provider's `api` port annotated `Propagation.MANDATORY` (§7, §19.5).

**Persistence**
14. Tier A modules (Workshop, Registration, Payment, Check-in): separate JPA entity, aggregate carries `Version`, repository adapter compares versions explicitly and throws `OptimisticConflictException` (§9.2). Tier B (User, Notification): transaction script (§9.1).
15. Query use cases return projections through `<Noun>Queries` ports under `@Transactional(readOnly = true)`; add `@ReadFromPrimary` for consistency-sensitive reads (§20).

**Messaging**
16. MUST NOT publish to RabbitMQ from business code; call `EventPublisher.publish(OutboxMessage)` inside the business transaction (§8.2, §8.3).
17. Every domain event goes through the module's exhaustive outbox translator; routing keys are copied verbatim from `messaging.md` §2 (§8.1, §8.2).
18. Every consumer is idempotent under redelivery: re-check state at the Master, or deduplicate external side effects by event id (§8.5).
19. Listeners never swallow exceptions; permanent failures throw `NonRetryableMessageException`; `AmqpRejectAndDontRequeueException` is banned (§8.6).

**Security, audit, errors, idempotency**
20. Endpoint authorization is `@PreAuthorize("hasAuthority(...)")` with `Permissions` constants on controllers; never a role name (§11).
21. Audited operations call `AuditTrail.record(...)` explicitly; it never throws and publishes after commit (§12).
22. Every error body is an RFC 7807 `ProblemDetail`; domain exceptions extend `DomainException` and declare a `ProblemKind` — never an HTTP status (§17).
23. Idempotent operations record the use case Result in the same transaction, keyed by `(scope, authenticated actor, key)` with a request fingerprint; business rejections are sealed Result variants, never thrown exceptions (§21, §25).

**Names & values**
24. Web DTO names, paths, headers and enum values are copied from `packages/api-contract`; table/column/status/scope names from `database-schema.md`. Never invent an alternative (§10, §14.2).
25. Constructor injection only; configuration via `@ConfigurationProperties`; no secrets in source (§15, §22).

---

## Table of Contents

- [Rules at a Glance](#rules-at-a-glance)
- [1. Module Boundary](#1-module-boundary)
- [2. Package Layout](#2-package-layout)
- [3. The Dependency Rule](#3-the-dependency-rule)
- [4. SOLID Mapping](#4-solid-mapping)
- [5. Domain Layer](#5-domain-layer)
- [6. Application Layer — Use Cases & Ports](#6-application-layer-use-cases-ports)
- [7. Module Interaction Types](#7-module-interaction-types)
- [8. Messaging — Outbox, Relay, Consumers](#8-messaging-outbox-relay-consumers)
- [9. Infrastructure Layer](#9-infrastructure-layer)
- [10. Web Layer](#10-web-layer)
- [11. RBAC & Authorization](#11-rbac-authorization)
- [12. Audit Trail](#12-audit-trail)
- [13. Shared Kernel](#13-shared-kernel)
- [14. Naming & Ubiquitous Language](#14-naming-ubiquitous-language)
- [15. Dependency Injection & Bean Naming](#15-dependency-injection-bean-naming)
- [16. Time](#16-time)
- [17. Errors — RFC 7807 Problem Details](#17-errors-rfc-7807-problem-details)
- [18. Validation](#18-validation)
- [19. Transactions, Locking & Concurrency](#19-transactions-locking-concurrency)
- [20. Read/Write Routing — CQRS-lite & Master/Replica](#20-readwrite-routing-cqrs-lite-masterreplica)
- [21. Idempotency](#21-idempotency)
- [22. Configuration & Secrets](#22-configuration-secrets)
- [23. Logging & Correlation](#23-logging-correlation)
- [24. Null-Safety & Immutability](#24-null-safety-immutability)
- [25. Worked Example — Seat Reservation End-to-End](#25-worked-example-seat-reservation-end-to-end)
- [26. Testability & Structural Enforcement](#26-testability-structural-enforcement)
- [27. Open Items in Other Documents](#27-open-items-in-other-documents)
- [28. Reference Table](#28-reference-table)

---

## 1. Module Boundary

**Rule:** a module may import from another business module **only** its `com.unihub.<module>.api..` package. Never `domain`, `application`, `web`, or `infrastructure` — not even to read a value type.

A module's `api` package is its whole public surface. It contains:

| Content | Example | Used for |
|---|---|---|
| Integration Event contracts (`api/event`) | `registration.api.event.RegistrationConfirmedIntegrationEvent` | Other modules deserializing a message (§7 type 4, §8) |
| Synchronous ports the module chooses to expose, plus their result types | `workshop.api.SeatInventory`, `workshop.api.SeatClaimOutcome` | Same-transaction commands and reads by another module (§7 types 1–2) |

Types that several modules must agree on — typed IDs (`WorkshopId`, `UserId`, `RegistrationId`), `Actor`, the `DomainException` base — live in the shared kernel (§13), **not** in any module's `domain`. That is what lets `api` signatures stay typed without leaking a module's domain model.

**Guarantee:** ArchUnit (§26) fails the build if any class in module A depends on a class in module B outside `B.api..`.

---

## 2. Package Layout

Every business module has this shape. The `shared` kernel (§13) follows the same shape.

```
com.unihub.<module>/
  domain/
    model/          aggregate roots, entities, value objects — plain Java (Tier A modules only, §9)
    event/          sealed <Module>DomainEvent + past-tense records
    exception/      subclasses of shared DomainException
  application/
    port/in/        one <Verb><Noun>UseCase interface per operation
    port/out/       outbound ports this module needs + the records they return
    usecase/        use case implementations, implementations of this module's api ports, *Transaction helpers
    command/        <UseCase>Command records (use case input)
    result/         <UseCase>Result records / sealed types (use case output)
    event/          Deferred Job payloads (module-private, §8.4) + the outbox translator
  api/              the ONLY package other modules may import (§1)
    event/          Integration Event contracts
  web/              HTTP driving adapter
    controller/
    dto/            classes named exactly after packages/api-contract schemas (§10)
    mapper/
  infrastructure/
    in/             driving adapters other than HTTP
      messaging/    @RabbitListener classes
      scheduling/   @Scheduled triggers
    out/            driven adapters
      persistence/  JPA entities, Spring Data interfaces, repository adapters, mappers
      client/       external systems (Stripe, S3, AI summarization, notification providers)
      crypto/       e.g. ticket signing (Registration)
      lock/         e.g. Redis seat lock (Registration)
    config/         module-local @Configuration and @ConfigurationProperties
```

The outbox writer, the relay, the broker client, the idempotency store, the audit adapter and the global error handler exist **once**, in `com.unihub.shared` (§13). They never appear inside a business module's tree.

`web` stays a top-level package even though it is a driving adapter like `infrastructure/in`. It is the largest adapter and the one the API contract governs, so keeping it separate keeps contract-related review easy.

---

## 3. The Dependency Rule

Source dependencies point inward only. Driving vs. driven adapters are separated **by package** (`infrastructure/in` vs. `infrastructure/out`), so every rule below is checkable by ArchUnit on package paths alone (§26).

| Package (in module M) | May depend on | Must not depend on |
|---|---|---|
| `M.domain` | JDK; `shared.domain` | Everything else, including Spring, JPA, Jackson |
| `M.application` | `M.domain`; `M.application..`; `M.api..`; other modules' `api..`; `shared.domain`, `shared.security`, `shared.event`, `shared.application.port.out`, `shared.application.routing`; Spring **only** `org.springframework.stereotype..` and `org.springframework.transaction.annotation..` | `M.web`, `M.infrastructure..`, any other Spring package, JPA, AMQP, Redisson, SDKs |
| `M.api` | JDK; `shared.domain`; `shared.event` | `M.domain`, `M.application`, `M.web`, `M.infrastructure` |
| `M.web` | `M.application.port.in`, `.command`, `.result`; `shared.domain`, `shared.security`, `shared.web`; Spring Web / Security / Validation; Jackson; springdoc annotations | `M.domain`, `M.application.port.out`, `M.application.usecase`, `M.infrastructure..` |
| `M.infrastructure.in..` | `M.application.port.in`, `.command`, `.event`; other modules' `api.event`; `shared.domain`, `shared.security`, `shared.messaging`; Spring AMQP / scheduling | `M.application.port.out`, `M.application.usecase`, `M.infrastructure.out..`, `M.domain.model` |
| `M.infrastructure.out..` | `M.domain`; `M.application.port.out` (and the records declared there); `M.application.event` (serialization only); other modules' `api..` (for cross-module read adapters, §6.3); `shared.domain`, `shared.application.port.out`; JPA, JDBC, Redisson, SDKs | `M.application.port.in`, `M.application.usecase`, `M.web`, `M.infrastructure.in..` |
| `M.infrastructure.config` | Anything inside M (it wires the module); `shared..` | Other modules' non-`api` packages |
| `shared..` | JDK, Spring, libraries | Any business module |

**Why the `application` allow-list exists.** `@Service` and `@Transactional` on use cases are standard Spring practice. Banning them would force hand-written proxies for no real decoupling gain. Everything beyond these two annotation packages stays forbidden, so `application` still cannot touch HTTP, persistence or messaging APIs.

**Guarantee:** swapping every adapter of a module for another implementation (CLI, gRPC, another database) needs no change in `domain` or `application`.

---

## 4. SOLID Mapping

| Principle | Concrete rule | Where |
|---|---|---|
| Single Responsibility | One use case per business operation; each adapter does one translation job | §6.1, §9 |
| Open/Closed | New behavior = new class behind an existing port (a new notification channel is a new `NotificationChannel` implementation, per `notification.md`); a growing `switch` on a provider or channel type is a review finding | §8, §9 |
| Liskov Substitution | Every implementation of a port honors the same contract, **including which exception it throws for which failure** — documented in the port's Javadoc | §6, §9 |
| Interface Segregation | Narrow ports at module boundaries; one repository per aggregate inside a module | §6.3 |
| Dependency Inversion | `application` depends only on interfaces it (or another module's `api`, or `shared`) defines; adapters implement them | §1, §3 |

---

## 5. Domain Layer

Applies to Tier A modules (§9). The domain is plain Java: no annotations, no framework types, and **no clock access** — time always comes in as a parameter.

```java
// workshop/domain/model/Workshop.java
public final class Workshop {
    private final WorkshopId id;           // shared.domain.id
    private WorkshopStatus status;
    private final Version version;         // mirrors workshops.version; opaque to business logic
    private final List<WorkshopDomainEvent> events = new ArrayList<>();

    public void cancel(Instant occurredAt) {
        if (status.isTerminal()) {
            throw new InvalidWorkshopStateException(id, status, WorkshopStatus.CANCELLED);
        }
        this.status = WorkshopStatus.CANCELLED;
        events.add(new WorkshopCancelled(id, occurredAt));
    }

    public List<WorkshopDomainEvent> pullEvents() {
        var pulled = List.copyOf(events);
        events.clear();
        return pulled;
    }

    public Version version() { return version; }
}
```

- **`Version`** (`shared.domain.Version`, wraps a `long`) is carried by every Tier A aggregate. It is the value the persistence adapter checks against (§9.2). Business logic never reads or changes it.
- **Domain events** are a sealed interface per module with past-tense record variants, so translating them (§8.1) is an exhaustive `switch` the compiler checks:
  ```java
  public sealed interface WorkshopDomainEvent permits WorkshopCancelled, WorkshopRescheduled {}
  public record WorkshopCancelled(WorkshopId workshopId, Instant occurredAt) implements WorkshopDomainEvent {}
  ```
- **Domain exceptions** extend `shared.domain.DomainException` and declare a `ProblemKind` (§17). They describe business-rule violations, never persistence or HTTP concerns.
- **Invariants live on aggregate methods** (`cancel`, `reserve`, `expire`), never behind public setters.
- **Value objects** are immutable records that validate themselves in their compact constructor and compare by value.

---

## 6. Application Layer — Use Cases & Ports

### 6.1 One use case per operation

```java
// workshop/application/port/in/CreateWorkshopUseCase.java
public interface CreateWorkshopUseCase {
    CreateWorkshopResult create(CreateWorkshopCommand command);
}
```

```java
// workshop/application/usecase/CreateWorkshop.java
@Service
class CreateWorkshop implements CreateWorkshopUseCase {
    private final WorkshopRepository workshops;
    private final AuditTrail auditTrail;
    private final Clock clock;

    CreateWorkshop(WorkshopRepository workshops, AuditTrail auditTrail, Clock clock) {
        this.workshops = workshops; this.auditTrail = auditTrail; this.clock = clock;
    }

    @Override
    @Transactional
    public CreateWorkshopResult create(CreateWorkshopCommand command) {
        var now = clock.instant();
        var workshop = Workshop.draft(WorkshopId.newId(), command.details(), now);
        workshops.save(workshop);
        auditTrail.record(AuditEntry.of(command.actor(), AuditActions.WORKSHOP_CREATED,
                                        AuditTargets.WORKSHOP, workshop.id().value(), now));
        return CreateWorkshopResult.from(workshop);
    }
}
```

This one-transaction shape is the common case. §19 and §25 cover the shapes that need more than one transaction or a lock.

### 6.2 Commands and results

- `<UseCase>Command` / `<UseCase>Result`, paired by use-case name, immutable records.
- A Command carries the calling `Actor` (§11) whenever the operation is authorized or audited — `Actor.UserActor` for HTTP-originated commands, `Actor.SystemActor` for job-originated ones.
- A Result that has more than one business outcome is a **sealed type**, not a record with nullable fields (see `ReserveSeatResult`, §25). The web layer maps each variant to one HTTP response.

### 6.3 Port granularity

- **Inside a module:** one `<Aggregate>Repository` per aggregate root (`save`, `findById`, …) shared by the module's use cases. Read-model queries go through a separate `<Noun>Queries` port (§20). No internal reader/writer split without a concrete consumer driving it.
- **Across modules — reads:** the consumer declares a narrow port in its own `application/port/out` (e.g. `registration.application.port.out.WorkshopTitles`). Its `infrastructure/out` adapter implements that port by calling the provider's `api`.
- **Across modules — same-transaction commands:** the provider declares the port in its own `api` (e.g. `workshop.api.SeatInventory`), and the consumer's `application` depends on that interface directly. The provider's `api` type already is the exact contract, so a consumer-side copy would add nothing.

### 6.4 Relaxing one-interface-per-use-case

The default for all modules is one `port/in` interface per use case. For a Tier B module (§9.3) whose use cases have almost no branching, a single `<Module>Operations` interface with a few related methods is allowed **after** the first two modules are built and the boilerplate is measured. Record that decision in the module's `technical-design/modules/*.md`; don't decide it in advance.

---

## 7. Module Interaction Types

| # | Type | Example | Mechanism |
|---|---|---|---|
| 1 | Read for display or a soft decision | Registration shows a workshop title | Consumer-owned port → adapter → provider `api`. May read a Replica (§20). |
| 2 | **Synchronous command inside the caller's transaction** | Claim/release a seat (`workshops.seats_taken`) on reserve, expire, cancel | Provider `api` port, implemented with `@Transactional(propagation = MANDATORY)` (§19.5) |
| 3 | Synchronous stateless computation | Signing a ticket's QR payload at `CONFIRMED` (`checkin.md` §4: Registration signs) | `port/out` in the owning module + an adapter in its `infrastructure/out` |
| 4 | Reaction after commit | Notification, summarization, reconciliation | Outbox → relay → RabbitMQ → listener (§8) |

Type 2 is a deliberate, narrow exception to "modules only talk asynchronously". Anti-overbooking requires the `registrations` write and the `workshops.seats_taken`/`version` update to commit **in one transaction** (`registration.md` §1, `database-schema.md` §3.1/§4.1). An event cannot provide that. The rule "Workshop is the sole writer of `workshops`" is kept at the **code** level: the SQL that changes `seats_taken` lives only in the Workshop module, and Registration can only *call* it.

An in-process call to another module's `api` is **not** an "external call" in the sense §19.1 restricts.

---

## 8. Messaging — Outbox, Relay, Consumers

### 8.1 Domain events are translated before publishing

A domain event never goes on the wire. Each module has one translator in `application/event` that maps every domain event variant — exhaustively — to zero or more outbox messages:

```java
// registration/application/event/RegistrationOutboxTranslator.java
final class RegistrationOutboxTranslator {
    static List<OutboxMessage> translate(RegistrationDomainEvent event) {
        return switch (event) {
            case SeatReserved e -> List.of(new SeatHoldExpiryRequested(          // Deferred Job, §8.4
                    e.registrationId(), e.workshopId(), e.holdExpiresAt(), e.occurredAt()));
            case RegistrationConfirmed e -> List.of(new RegistrationConfirmedIntegrationEvent(
                    e.registrationId(), e.workshopId(), e.userId(), e.occurredAt()));
            case SeatHoldExpired e -> List.of();   // explicit: nothing to publish
        };
    }
}
```

Adding a domain event variant does not compile until the translator decides what, if anything, it publishes.

### 8.2 The outbox message contract

```java
// shared/event/OutboxMessage.java
public interface OutboxMessage {
    String routingKey();      // exact key from messaging.md §2, declared as a constant on the record
    String aggregateType();   // event_publications.aggregate_type
    UUID aggregateId();       // event_publications.aggregate_id
    int schemaVersion();
    Instant occurredAt();
}
```

```java
// registration/api/event/RegistrationConfirmedIntegrationEvent.java
public record RegistrationConfirmedIntegrationEvent(
        UUID registrationId, UUID workshopId, UUID userId, Instant occurredAt)
        implements OutboxMessage {
    public static final String ROUTING_KEY = "unihub.registration.ticket.confirmed"; // messaging.md §2
    public static final int SCHEMA_VERSION = 1;
    @Override public String routingKey()    { return ROUTING_KEY; }
    @Override public String aggregateType() { return "REGISTRATION"; }
    @Override public UUID aggregateId()     { return registrationId; }
    @Override public int schemaVersion()    { return SCHEMA_VERSION; }
}
```

Integration events carry plain `UUID`s, not the id value objects (`RegistrationId`, `WorkshopId`): a value object serializes as `{"value": "…"}`, which would make every consumer parse a nested object. `shared.domain` is plain Java and cannot carry Jackson annotations, so the flat form is chosen at the contract (decided 2026-10-19, first events: `WorkshopCancelledIntegrationEvent`, `WorkshopRescheduledIntegrationEvent`). The translator unwraps with `.value()`.

**Transport metadata is not part of the business payload:**

| Metadata | Set by | Carried as |
|---|---|---|
| `eventId` | `OutboxEventPublisher`, at insert | `event_publications.id`, then the AMQP `message-id` property |
| Correlation ID | `OutboxEventPublisher`, read from the MDC key `correlationId` (`observability.md` §1: the trace ID *is* the correlation ID) | `event_publications.correlation_id` (`database-schema.md` §8.2). At publish time the relay puts the stored value back into the MDC, so the global `RabbitTemplate` post-processor (`observability.md` §1) writes it into the `correlationId` message header, then clears the MDC in `finally` |
| `schemaVersion` | `OutboxMessage.schemaVersion()` | A top-level field **inside** `event_publications.payload`: the table has no column for it and a consumer needs it in the body to reject a version it does not know (§8.6). The only metadata that lives in the payload (decided 2026-10-19, Phase 2 checklist 1.3) |
| `event_type` | Simple class name of the record | `event_publications.event_type` (diagnostic label only) |
| Routing key | `OutboxMessage.routingKey()` | `event_publications.routing_key` — the relay publishes with this stored value and never derives it |

Routing keys are always copied verbatim from `messaging.md` §2 and never invented here.

### 8.3 Components

| Component | Package | Role |
|---|---|---|
| `EventPublisher` (port) | `shared.application.port.out` | `void publish(OutboxMessage message)` — called by use cases inside their transaction |
| `OutboxEventPublisher` | `shared.infrastructure.out.persistence` | Inserts one `event_publications` row in the caller's transaction. Never touches RabbitMQ. |
| `RelayPendingEventsUseCase` (port-in) / `RelayPendingEvents` (use case) | `shared.application.port.in` / `shared.application.usecase` | Reads `PENDING` rows through `PendingEventStore`, publishes each through `MessageBroker`, marks it `PUBLISHED` after the publisher confirm. **Not `@Transactional`**: every store call is its own autocommit statement. A `BrokerPublishException` leaves that row `PENDING` and the cycle goes on; a `BrokerUnavailableException` ends the cycle |
| `PendingEventStore` (port) / `JdbcPendingEventStore` | `shared.application.port.out` / `shared.infrastructure.out.persistence` | `findPending(limit)` and `markPublished(id)`. No transaction annotation on purpose: with none open, the routing datasource sends the statement to the Master (a Replica read could return a row already marked `PUBLISHED`) |
| `MessageBroker` (port) / `RabbitMessageBroker` | `shared.application.port.out` / `shared.infrastructure.out.messaging` | The only class that calls `RabbitTemplate` for outbox traffic. Publishes the stored JSON as raw bytes (never through the JSON converter, which would encode it twice) and returns only after the confirm **and** a check that the message was not returned as unroutable |
| `EventRelayScheduler` | `shared.infrastructure.in.scheduling` | `@Scheduled(fixedDelay)` trigger: takes the `unihub:lock:job:outbox-relay` job lock (skip the cycle if not acquired), then calls `RelayPendingEventsUseCase`. On by default (`unihub.jobs.outbox-relay.*`) |
| `PurgeEventPublicationsUseCase` / `PurgeEventPublications` / `EventPublicationCleanupScheduler` | `shared.application.port.in` / `.usecase` / `shared.infrastructure.in.scheduling` | Deletes `PUBLISHED` rows older than the retention (1 hour from `published_at`, `unihub.jobs.event-publication-cleanup.*`) under `unihub:lock:job:event-publication-cleanup`. Never deletes a `PENDING` row |

**Relay mechanism — decided in `messaging.md` §5:** one instance per cycle behind the scheduled-job lock; it reads a batch of `PENDING` rows from the Master **without row locks and without an open transaction**; for each row it restores `correlation_id` into the MDC, publishes with `message-id = id` and `mandatory = true`, waits for the publisher confirm, and marks the row `PUBLISHED` in its own autocommit update. A row that fails is left `PENDING` and skipped, so it never blocks the rows behind it. Code must not deviate from this shape: in particular, a `SELECT … FOR UPDATE SKIP LOCKED` held across the publish is **forbidden** (§19.1). The multi-instance claim-with-lease variant is the documented upgrade path, not an alternative to pick ad hoc.

### 8.4 Deferred Jobs (self-addressed messages)

Three of the four outbox message kinds in `architecture.md` §4.3 are addressed to the module that publishes them: seat-hold expiry, summarization, reconciliation. They use the same outbox and relay, but their payload lives in `application/event` (module-private, never in `api`), because no other module deserializes them.

```java
// registration/application/event/SeatHoldExpiryRequested.java
public record SeatHoldExpiryRequested(RegistrationId registrationId, WorkshopId workshopId,
                                      Instant holdExpiresAt, Instant occurredAt) implements OutboxMessage {
    public static final String ROUTING_KEY = "unihub.registration.hold.reserved"; // delay queue, messaging.md §2
    // routingKey()/aggregateType()/aggregateId()/schemaVersion() as in §8.2
}
```

The delay itself comes from the queue's `x-message-ttl` (`registration.md` §3), not from any field on the message. The consumer always re-reads `hold_expires_at` and `status` from the Master (§8.7).

### 8.5 Consumers

A listener is a driving adapter in `infrastructure/in/messaging`. It maps the message to a Command and calls exactly one `port/in`. It contains no business logic and never touches a repository.

```java
// registration/infrastructure/in/messaging/SeatHoldExpiryListener.java
@Component
class SeatHoldExpiryListener {
    private final ExpireSeatHoldUseCase expireSeatHold;

    SeatHoldExpiryListener(ExpireSeatHoldUseCase expireSeatHold) { this.expireSeatHold = expireSeatHold; }

    @RabbitListener(queues = "unihub.seat-hold-expiry.queue")
    void onHoldExpired(SeatHoldExpiryRequested message) {
        expireSeatHold.expire(new ExpireSeatHoldCommand(message.registrationId(), message.workshopId()));
    }
}
```

The shared `Jackson2JsonMessageConverter` is configured to infer the target type from the listener parameter, not from a class-name header, so a producer's class name never becomes part of the wire contract.

**Exception — a queue bound to several payload types** (today only `unihub.notification.queue`; Phase 4 checklist D34): the listener takes the raw `Message`, chooses the payload class from the AMQP `type` property (= the routing key, set by the relay, `messaging.md` §2), and converts it itself; a missing or unknown type that its documented fallback cannot resolve is a `NonRetryableMessageException`. The wire contract is still the routing key, never a class name.

**Idempotency under redelivery** (`architecture.md` §4.3: at-least-once). This is a different mechanism from `idempotency_keys`, which guards HTTP retries only (`messaging.md` §4, "Critical distinction").

| Consumer effect | Mechanism |
|---|---|
| State transition guarded by status/version (release an expired hold, resolve a pending payment) | Re-read current state at the Master inside the transaction; if it is already resolved, commit a no-op. No extra table. |
| Compensation when the guarded transition no longer applies (payment succeeded but the registration is no longer `RESERVED`) | The conditional `UPDATE … WHERE status = 'RESERVED'` matches zero rows → re-read → publish a compensating event (e.g. `unihub.registration.confirmation.rejected`) instead of the transition. A redelivery publishes it again; the receiver deduplicates by its own conditional transition. No inbox table — `architecture.md` §6.1 has exactly three Technical Pattern Entities. |
| External side effect keyed by an aggregate (refund a payment — Phase 3) | The conditional transition (`SUCCEEDED → REFUND_REQUIRED`) is the dedup point; the gateway call uses a key derived from the aggregate (`refund:{paymentId}`), never from the event id, because the compensating event is re-emitted on every redelivery. Every transition into `REFUND_REQUIRED` writes a `refund.requested` Deferred Job in the same transaction, and only that job's consumer calls the gateway (Phase 3 checklist D26, which replaces the earlier "the rejected consumer calls the gateway again" rule): the outbox, not a re-run of the transition, covers a crash between commit and the call. That consumer lists the gateway's existing refunds before creating one, and re-processing while still `REFUND_REQUIRED` calls the gateway again; only `REFUNDED` is a no-op (`payment.md` §9). |
| External side effect (send an email) | Transaction 1: insert the `notifications` row with `ON CONFLICT DO NOTHING` on `UNIQUE (source_event_id, user_id, channel)`, `source_event_id` = the message's `message-id` (§27, item 5). Then, per row still `PENDING`: **claim** it with a conditional `UPDATE … SET claimed_at = now() WHERE status = 'PENDING' AND (claimed_at IS NULL OR claimed_at < now() - <lease>)` (zero rows → skip), call the provider **outside** any transaction, and mark it in a short transaction: `SENT`, `FAILED` for a per-recipient permanent rejection, or left `PENDING` with its claim released when delivery failed transiently (for this recipient, or for the whole channel). If any row of the event is still `PENDING` afterwards (unavailable, or skipped under another delivery's claim), the use case throws so the message is retried — never acked with a row `PENDING`. On redelivery `SENT`/`FAILED` rows are skipped and `PENDING` rows are sent again. The unique key stops duplicate rows; only the claim stops two concurrent deliveries sending the same row. A crash after the provider accepted but before the mark means one more send — an accepted at-least-once trade-off, not something assumed away (`notification.md` §3, §5; Phase 4 checklist D31–D33). |

### 8.6 Consumer failure handling

`messaging.md` §4 is authoritative. Business queues have **no dead-letter exchange** (the one exception is `unihub.audit.queue`, bounded with `reject-publish-dlx` into the parking lot — `messaging.md` §3, Phase 6 checklist D70); retries and the parking-lot are reached only through an **application-level republish** by the shared error handler. Consequences for code:

- A listener never catches and swallows an exception. It lets it propagate to the shared error handler, `RetryChainRecoverer` (`shared.infrastructure.out.messaging`: it publishes, so it is a driven adapter and the ArchUnit rule for `infrastructure.in` forbids it there). It is wired into every listener container once, by `ListenerContainerConfig`, as the recoverer of a stateless retry interceptor with `maxAttempts(1)`: no in-process retry, the first failure goes to the recoverer. It republishes to the next retry stage using the `x-death` counts — **summed over the three stage queues of this queue's own chain**, because each stage is a different queue and so a different `x-death` entry — or to `unihub.parking-lot` once the three retries are used. The republish waits for a publisher confirm; if it cannot be confirmed the original is **requeued** (`ImmediateRequeueAmqpException`), never rejected, so a failure of the error handler cannot lose the message.
- Permanent failures: besides `NonRetryableMessageException`, a payload that cannot be converted (`MessageConversionException`) goes straight to the parking lot without reaching the listener.
- The first failure records `x-original-routing-key`, `x-original-exchange` and `x-original-queue` on the message and every hop carries them, because after a retry hop the received routing key is the stage's fixed dead-letter key. The parking-lot message adds `x-exception-message` and `x-exception-class`.
- `CorrelationIdListenerAdvice` (`shared.infrastructure.in.messaging`), also wired once by `ListenerContainerConfig`, restores the `correlationId` header into the MDC for one delivery, clears it in `finally`, and logs every delivery (event id, routing key, queue, correlation id) at `INFO` (§23).
- A permanent failure (unparseable payload, unsupported `schemaVersion`) is signalled by throwing `shared.messaging.NonRetryableMessageException`. The error handler sends it **straight to `unihub.parking-lot`**, with the original routing key and the exception message as headers.
- `AmqpRejectAndDontRequeueException` is **banned** in this codebase: on a queue without a dead-letter exchange it silently drops the message.
- `spring.rabbitmq.listener.simple.default-requeue-rejected=false`.

### 8.7 Ordering

There is no global ordering (`architecture.md` §4.3). Every consumer re-checks current state at the Master before acting and never infers order from the message.

---

## 9. Infrastructure Layer

### 9.1 Tiering — where a separate domain model pays off

| Tier | Modules | Persistence shape |
|---|---|---|
| **A** | Workshop, Registration, Payment, Check-in | Aggregate in `domain/model`, separate package-private `<Noun>JpaEntity`, mapper, repository adapter. These modules have races (`version`), state machines, or reconciliation rules worth a framework-free, unit-testable core. |
| **B** | User, Notification | Transaction script: use case → `port/out` → adapter. The port returns records **declared next to the port** (e.g. `user.application.port.out.UserAccountRow`), never the use case's `result` types. No aggregate, no mapper. User's nightly sync is a batch upsert (use JDBC batching, not per-row aggregates); Notification writes a row and dispatches. |

**Exception — Check-in (Phase 5 checklist 3.3).** Check-in is Tier A in its core and Tier B in its persistence. The reconciliation rules are a framework-free, unit-tested core in `domain/model` (`ReconciliationPolicy`), which is the reason Tier A exists, but `check_in_scans` and `attendances` have no `version` column and their concurrency control is a Postgres advisory lock per registration plus `ON CONFLICT` (`checkin.md` §7), so the adapters are JDBC (`JdbcCheckInScans`, `JdbcAttendances`) behind `port/out`, with no JPA entity and no `OptimisticConflictException`.

A Tier B module that later grows a real invariant moves to Tier A. That move is a refactor inside the module and invisible to other modules.

### 9.2 Tier A persistence and optimistic locking

```java
// workshop/infrastructure/out/persistence/JpaWorkshopRepository.java
@Repository
class JpaWorkshopRepository implements WorkshopRepository {
    private final SpringDataWorkshopRepository springData;   // package-private Spring Data interface
    private final WorkshopPersistenceMapper mapper;

    @Override
    public Optional<Workshop> findById(WorkshopId id) {       // absent → Optional, never an exception
        return springData.findById(id.value()).map(mapper::toDomain);
    }

    @Override
    public void save(Workshop workshop) {
        var entity = springData.findById(workshop.id().value()).orElse(null);
        if (entity == null) {
            springData.save(mapper.toNewEntity(workshop));
            return;
        }
        if (entity.version() != workshop.version().value()) {  // explicit check, see below
            throw new OptimisticConflictException("WORKSHOP", workshop.id().value());
        }
        mapper.copyState(workshop, entity);                    // never writes entity.version
        // Hibernate issues UPDATE … WHERE id = ? AND version = ? and increments version on flush
    }
}
```

**Why the explicit check is mandatory.** For an entity already managed in the persistence context, Hibernate **ignores** a value you assign to its `@Version` field and uses the version from its own load-time snapshot. Copying `workshop.version()` into the entity therefore does nothing. The adapter must compare explicitly and throw. `OptimisticConflictException` (`shared.domain`) is the one exception every repository port uses for a lost race (Liskov, §4).

**Status-guarded transitions** (`registration.md` §3/§5, `payment.md` §9): the adapter may add the expected status to the same statement — `UPDATE … WHERE id = ? AND version = ? AND status = 'RESERVED'` — and treat affected rows ≠ 1 as `OptimisticConflictException`; the use case then re-reads and branches (no-op, or compensation). It is the same optimistic check, made explicit about the state it guards.

The use case, not the adapter, decides what an empty `Optional` means (usually a `…NotFoundException` with `ProblemKind.NOT_FOUND`).

### 9.3 Other driven adapters

Each implements exactly one port and contains translation only: `StripePaymentGateway` (with the Resilience4j `paymentGateway` breaker, `payment.md`), `S3BrochureStorage`, `EcdsaTicketSigner`, `RedissonSeatLock`, `<Channel>NotificationChannel`. Only `infrastructure.out..` may import `jakarta.persistence`, JDBC, Redisson, the AWS SDK, the Stripe SDK or JOSE libraries.

### 9.4 Schedulers

`infrastructure/in/scheduling` classes are triggers only: they acquire the job lock where required (`architecture.md` §4.6, `distributed-coordination.md` §2) and call exactly one `port/in`. Example: `NightlySyncScheduler` → `RunNightlySyncUseCase`.

---

## 10. Web Layer

A controller translates HTTP into a Command, calls exactly one `port/in`, and maps the Result back to HTTP. No business branching.

```java
// workshop/web/controller/WorkshopController.java
@RestController
@RequestMapping("/api/v1/workshops")                      // path exactly as in packages/api-contract
class WorkshopController {
    private final CreateWorkshopUseCase createWorkshop;   // port/in, never the implementation class

    WorkshopController(CreateWorkshopUseCase createWorkshop) { this.createWorkshop = createWorkshop; }

    @PostMapping
    @PreAuthorize("hasAuthority(T(com.unihub.shared.security.Permissions).WORKSHOP_CREATE)")
    ResponseEntity<Workshop> create(@Valid @RequestBody CreateWorkshopRequest request,
                                    @AuthenticationPrincipal Jwt jwt) {
        var result = createWorkshop.create(WorkshopWebMapper.toCommand(request, CurrentActor.from(jwt)));
        return ResponseEntity.status(HttpStatus.CREATED).body(WorkshopWebMapper.toResponse(result));
    }
}
```

**Contract alignment rules** (checked by contract-diff, M-17):

- Class names in `web/dto` equal the schema names in `packages/api-contract/schemas/*.yaml` — `CreateWorkshopRequest`, `Workshop`, `WorkshopSummary`, `WorkshopListResponse`, `CreateRegistrationRequest`, `Registration`. Never an invented name such as `WorkshopResponse`. A DTO sharing a simple name with a domain class (`web.dto.Workshop` vs. `domain.model.Workshop`) is expected: `web` cannot import `domain.model` (§3), so the two never meet.
- Paths, HTTP methods, status codes and headers (`Idempotency-Key`, `X-Correlation-Id`, `Retry-After`) match `packages/api-contract/paths/*.yaml`.
- Enum values in DTOs (`RegistrationStatus`: `RESERVED`, `CONFIRMED`, `CANCELLED`, `EXPIRED`) are copied from the contract.

---

## 11. RBAC & Authorization

Two layers, each in its natural place (`architecture.md` §5.1, `auth-and-identity.md`):

1. **Endpoint permission check** — on the controller, `@PreAuthorize("hasAuthority(...)")` against the permission codes in `shared.security.Permissions`. It is never on a use case, because use cases are also invoked from listeners and schedulers where no JWT exists.
2. **Data-level rules** (for example, whether a caller may act on a particular resource) — business rules in the use case or domain, evaluated against the `Actor` carried on the Command.

```java
// shared/security/Permissions.java — values are permissions.code rows (database-schema.md, user.md)
public final class Permissions {
    public static final String WORKSHOP_CREATE = "WORKSHOP_CREATE";
    public static final String WORKSHOP_EDIT   = "WORKSHOP_EDIT";
    public static final String CHECKIN_SCAN    = "CHECKIN_SCAN";
    public static final String REPORT_VIEW     = "REPORT_VIEW";
    public static final String REGISTRATION_CREATE = "REGISTRATION_CREATE";   // create a registration and pay for one's own (D7)
    private Permissions() {}
}

// shared/security/Actor.java — who performs an operation (full signature: §13.1)
public sealed interface Actor permits Actor.UserActor, Actor.SystemActor {
    record UserActor(UserId userId, Set<String> permissions) implements Actor { ... }  // a person, from the JWT
    record SystemActor(String process) implements Actor { ... }                       // a job, e.g. "seat-hold-expiry"
}
```

- Commands built from an HTTP request carry `Actor.UserActor` — a type-level guarantee that a user is present. `Actor.SystemActor` is used only by use cases triggered by a listener or scheduler for operations no person performs (§12).
- `CurrentActor.from(Jwt)` (`shared.web`) builds the `Actor.UserActor` from the `sub` and `permissions[]` claims. The JWT authorities converter maps `permissions[]` to authorities **with an empty prefix**, so `hasAuthority('WORKSHOP_CREATE')` matches the claim value exactly.
- `hasRole(`, `ROLE_` and any comparison against a role name are banned in `src/main` — the static evidence for INV-01 (§26).
- Add a new permission as a constant here **and** as a `permissions` row via migration, never one without the other.

---

## 12. Audit Trail

Audit is an explicit port, called by use cases — not an AOP aspect. An aspect cannot reliably know *what* happened *to which target*, and those are required fields (`architecture.md` §5.5, `database-schema.md` §8.3).

```java
// shared/application/port/out/AuditTrail.java
public interface AuditTrail {
    /** Never throws. Publishes after the surrounding transaction commits, or immediately if there is none. */
    void record(AuditEntry entry);
}

// shared/application/port/out/AuditEntry.java — fields mirror audit_events
public record AuditEntry(Actor actor, String action, String targetType, UUID targetId,
                         Instant occurredAt, Map<String, Object> metadata) { ... }
```

The Phase 6 adapter (`shared.infrastructure.out.messaging.RabbitAuditTrail`, decided 2026-11-02, Phase 6 checklist D68) must:

1. If a transaction synchronization is active, publish in `afterCommit` — never audit an operation that rolled back. Otherwise publish immediately. Calling `registerSynchronization` without an active synchronization throws, so this check is required.
2. Publish directly to `unihub.system.audit.created` — **not** through the outbox (`messaging.md` §3).
3. Catch every exception, log at `WARN`, and increment a failure counter so the gap surfaces as an alert (fail-open but not silent, `architecture.md` §5.5).
4. Map the actor exactly as `audit_events` stores it (`database-schema.md` §8.3): `UserActor` → `actor_type = 'USER'`, `actor_user_id = userId`; `SystemActor` → `actor_type = 'SYSTEM'`, `actor_user_id = NULL`, and `process` written to `metadata.process`.
5. **Never touch the broker on the calling thread.** The after-commit step only hands the entry to a bounded executor (1 thread, 10,000 entries); a full queue drops the entry and counts `reason="dropped"`. Snapshot the MDC `correlationId` and the observation context when `record()` is called and restore them on the executor thread, or the message leaves without either (`observability.md` §1, §8).
6. Publish through **its own** `CachingConnectionFactory` with publisher confirms; 5 s confirm, connection and RPC timeouts. Count `nack`, `timeout` and `error` separately on `unihub_audit_publish_failures_total{reason}`, and **register every `reason` at 0 at startup** (a series born above 0 is invisible to `increase()`). No meter of the same name may be registered without the tag.
7. Set a fresh UUID as the AMQP `message-id` of each entry — the consumer deduplicates on it (`messaging.md` §3).
8. At shutdown, drain the queue for up to 5 s and count what is left as `dropped`.

**Consumer (Phase 6 checklist D69):** the listener on `unihub.audit.queue` lives in `shared.infrastructure.in.messaging` — Audit Event is a Technical Pattern Entity owned by `shared` (§13), like the scheduler and listener advice already there. It inserts `ON CONFLICT (source_message_id) DO NOTHING` and has an entry in `ConsumerRedeliveryCoverageTest`. `LoggingAuditTrail` is **replaced**, not kept alongside, together with the `AsyncAppender` of the `unihub.audit` logger; `AuditPublishingAbsentTest` is replaced in the same commit.

**As built (Phase 6 checklist 2.1–2.5, 2026-11-03):** `AuditTrail`, `AuditEntry`, `AuditActions` and `AuditTargets` stay in `shared.application.port.out`. The adapter is `shared.infrastructure.out.messaging.RabbitAuditTrail`, built by `AuditConfig` (never as a `ConnectionFactory` or `RabbitTemplate` bean: Boot's own back off from either). `record()` formats the entry (the keys of `audit_events` plus `schemaVersion: 1`; `process` in `metadata` for a `SYSTEM` actor), snapshots the MDC `correlationId`, a Micrometer `ContextSnapshot` and the current `TraceContext` (the observation current at that moment is often a no-op left open by the JDBC layer, so the trace context is what carries the request's trace to the executor thread), and hands a job to a one-thread daemon `ThreadPoolExecutor` over an `ArrayBlockingQueue` (`unihub.audit.executor-queue-capacity`, 10,000) — in `afterCommit` when a synchronization is active, at once otherwise. The executor thread calls the `AuditPublisher` seam; its one implementation, `AmqpAuditPublisher`, owns the `CachingConnectionFactory` and `RabbitTemplate` (5 s connection, handshake, channel-RPC and confirm timeouts: `unihub.audit.*`), sets the `correlationId` header itself (the Boot template's post-processor does not apply to it) and maps the end of a send to a `reason`: confirm `nack` → `nack`; no confirm in time, or a channel that cannot be opened in time → `timeout`; a refused connection, an unroutable return or any other exception → `error`. A refused hand-off (queue full, executor shut down) is `dropped`. All four series of `unihub_audit_publish_failures_total{reason}` are registered at 0 in the constructor, on the injected `MeterRegistry`; every lost entry also logs one `WARN` line (action and target, never the metadata). `destroy()` shuts the executor down, waits `unihub.audit.drain-timeout` (5 s), counts what is still queued as `dropped` and interrupts the entry in flight, which counts itself once; the publisher is destroyed afterwards (the trail depends on it), and closing its connection frees a thread stuck on a blocked socket. The consumer is `shared.infrastructure.in.messaging.AuditEventListener` → `RecordAuditEventUseCase` → `AuditEventStore` (`JdbcAuditEventStore`, `INSERT … ON CONFLICT (source_message_id) DO NOTHING`); a message without a `message-id`, with another `schemaVersion`, or that cannot be read is a `NonRetryableMessageException`. `LoggingAuditTrail`, its test and the `AsyncAppender` of the `unihub.audit` logger are gone; `AuditPublishingRulesTest` replaced `AuditPublishingAbsentTest`. Use cases did not change. Tests that ask what a use case recorded use `RecordedAudit` (the real trail, a recording `AuditPublisher`), not the table.

**Phase 2 record (kept for history, superseded above; checklist 1.4, 2026-10-19):** the adapter was `shared.infrastructure.out.audit.LoggingAuditTrail`. It wrote one JSON line per entry (`actorType`, `actorUserId` for a user, `action`, `targetType`, `targetId`, `occurredAt`, `metadata` — with `process` for a `SYSTEM` actor) on the logger `unihub.audit`, through an `AsyncAppender` with `neverBlock=true`; a full queue dropped the line uncounted. An entry that could not be formatted or written incremented `unihub.audit.publish.failures` on the global registry.

**Phase 2 scope (checklist D6, Q10; C32):** the **port** is built in Phase 2 and every write use case calls it from then on — workshop create/update/cancel/reschedule/publish, registration create/confirm, payment charge and every payment transition, and the refund (Phase 3). The Phase 2 adapter is **`LoggingAuditTrail`**: the same after-commit, never-throw, failure-counter rules as below, but it writes one structured log line through a dedicated logger with a non-blocking asynchronous appender instead of publishing. It does **not** publish to the broker: a queue without a consumer grows without bound, and a RabbitMQ memory alarm blocks every publisher — the outbox relay and registration confirmation included. `RabbitAuditTrail` (below) and the consumer that persists `audit_events` are built together in Phase 6 (M-54); swapping the adapter changes no use case.

Action names follow the `audit_events.action` examples (`WORKSHOP_CANCELLED`, `ROLE_PERMISSIONS_CHANGED`) and live as constants in `shared.application.port.out.AuditActions`. Operations no person performs — a hold expiring, the reconciliation sweep resolving a payment — are audited with `Actor.system("<job-name>")`. They are never attributed to a real user (for example the student whose hold expired): that would make the audit trail false.

---

## 13. Shared Kernel

`com.unihub.shared` holds everything that genuinely crosses modules. It never depends on a business module.

| Package | Contents |
|---|---|
| `shared.domain` | `DomainException`, `ProblemKind`, `RetryableUnavailable`, `OptimisticConflictException`, `Version`; `shared.domain.id`: `UserId`, `WorkshopId`, `RegistrationId`, `PaymentId` |
| `shared.security` | `Permissions`, `Actor` |
| `shared.event` | `OutboxMessage` |
| `shared.messaging` | `NonRetryableMessageException` |
| `shared.application.port.out` | `EventPublisher`, `MessageBroker`, `AuditTrail` (+ `AuditEntry`, `AuditActions`, `AuditTargets`), `IdempotencyStore` (+ `IdempotencyRecord`, `StoredResult`, `IdempotencyScope`, `IdempotencyKeyInUseException`, `IdempotencyKeyReusedException`), `IdempotencyKeyCleanup`, `PendingEvent` + `PendingEventStore`, `BrokerPublishException`, `BrokerUnavailableException`, `EventPublicationCleanup`, `OutboxBacklog` and `ReplicaLag` (the two reads behind the database gauges, Phase 6) |
| `shared.application.routing` | `@ReadFromPrimary` (§20) |
| `shared.application.port.in` | `PurgeIdempotencyKeysUseCase`, `RelayPendingEventsUseCase`, `PurgeEventPublicationsUseCase` |
| `shared.application.usecase` | `RelayPendingEvents`, `PurgeIdempotencyKeys`, `PurgeEventPublications` |
| `shared.web` | `GlobalProblemHandler` (§17), `Problems` (builds a `ProblemDetail` response for a Result variant), `CurrentActor`, `RequestFingerprint` (§21 rule 4), `CorrelationIds`, `CorrelationIdFilter` and `CorrelationIdProblemAdvice` (Phase 6: the trace id as the correlation id, on the response header and in every problem body) |
| `shared.infrastructure..` | `OutboxEventPublisher`, `JdbcPendingEventStore`, `JdbcEventPublicationCleanup`, `JdbcIdempotencyStore`, `JdbcIdempotencyKeyCleanup`, `IdempotencyCleanupScheduler`, `EventRelayScheduler`, `EventPublicationCleanupScheduler`, `RabbitMessageBroker`, `ConfirmedSender`, `Topology` (the whole of `messaging.md` §2/§4 as data), `RetryChainRecoverer` (the listener error handler), `CorrelationIdPostProcessor`, `CorrelationIdListenerAdvice`, `RabbitMessagingConfig`, `ListenerContainerConfig`, datasource routing, the `Clock` bean; **Phase 6:** `RabbitAuditTrail`, `AmqpAuditPublisher` and `AuditEventListener` (the audit consumer, §12), `JobCycle` (a trace of its own for each scheduled cycle), `BackgroundGauges`, `BackgroundGauge` and `DatabaseGauges` (`infrastructure.out.metrics`: a gauge that reads PostgreSQL is refreshed on a thread of its own, never on a scrape), `JdbcOutboxBacklog` and `JdbcReplicaLag`, `DependencyHealthConfig`, `BoundedProbe`, `BoundedHealthIndicator` and `DependencyUpGauges` (`infrastructure.health`), `ManagementSecurityConfig` (the management port's own chain), `OutboundCall` (`infrastructure.out.observation`: the span and timer of a call to a third party) and `ScrubbedSpanExporter` with `TracingConfig` (`infrastructure.tracing`: no exception text on any span) |

Event Publication, Idempotency Key and Audit Event are *Technical Pattern Entities* (`architecture.md` §6.1). They belong to `shared`, not to any business module.

Keep `shared` small. A type goes into `shared` only if at least two modules need to agree on it. Convenience helpers do not qualify.

**Rate limiter (Phase 7 checklist D90, not built yet).** Rate limiting is applied to endpoints of several modules (`distributed-coordination.md` §3), so it lives in `shared`: a port in `shared.application.port.out` with its Redisson adapter in `shared.infrastructure.out`, and the two filters in `shared.web`. Rules: (1) the general budget is keyed by the authenticated user, else by the client address the edge gateway sets and overwrites (`X-Envoy-External-Address`, read only when the peer is the pod's own sidecar `127.0.0.6`; otherwise the socket's remote address) — never by `X-Forwarded-For`, and no `RemoteIpValve` (`distributed-coordination.md` §3); an unauthenticated or invalid-token request is charged to its address in the authentication entry point; (2) every Redis call has a short timeout and, on failure, the request goes through (fail open) **and** `unihub_rate_limit_bypassed_total{reason}` is incremented — a bypass is never silent; (3) a `429` is a problem body with `correlationId` and `Retry-After`, and every operation that can return it declares it in the contract — the payment webhook is exempt; (4) no in-memory, per-pod limiter, not even as a fallback (INV-17).

**Migrations (Phase 7 checklist D88, INV-15).** A new migration only expands the schema; the contract step ships one release later (`database-schema.md` §10). `MigrationLintTest` enforces it.

### 13.1 Shared kernel signatures

Every type this document uses from `shared` has exactly this shape. Implement it this way; do not invent a variant. Method bodies are omitted only where they are obvious.

```java
// ---------- shared.domain ----------
public abstract class DomainException extends RuntimeException {
    protected DomainException(String message) { super(message); }
    protected DomainException(String message, Throwable cause) { super(message, cause); }
    public abstract ProblemKind kind();
    /** The problem `type` URI when a client must branch on this reason (§17); null keeps `about:blank`. */
    public String problemType() { return null; }
}
public enum ProblemKind { INVALID_REQUEST, FORBIDDEN, NOT_FOUND, CONFLICT, UNPROCESSABLE, UNAVAILABLE }

/** Implemented by an UNAVAILABLE DomainException that should carry a Retry-After header. */
public interface RetryableUnavailable { Duration retryAfter(); }

public final class OptimisticConflictException extends DomainException {
    public OptimisticConflictException(String aggregateType, UUID aggregateId) {
        super(aggregateType + " " + aggregateId + " was modified concurrently");
    }
    @Override public ProblemKind kind() { return ProblemKind.CONFLICT; }
}

public record Version(long value) {
    public static Version initial() { return new Version(0); }
}

// ---------- shared.domain.id — one record per ID, all identical in shape ----------
public record WorkshopId(UUID value) {
    public WorkshopId { Objects.requireNonNull(value); }
    public static WorkshopId newId() { return new WorkshopId(UUID.randomUUID()); }
    public static WorkshopId of(UUID value) { return new WorkshopId(value); }
}
// UserId, RegistrationId, PaymentId: same shape.

// ---------- shared.security ----------
/**
 * Who performs an operation. Maps 1:1 onto audit_events.actor_type / actor_user_id (database-schema.md §8.3).
 * The nested names avoid "User"/"System", which would shadow UserId-adjacent names and java.lang.System.
 */
public sealed interface Actor permits Actor.UserActor, Actor.SystemActor {

    /** A person, resolved from the JWT. Every HTTP-originated Command carries this type. */
    record UserActor(UserId userId, Set<String> permissions) implements Actor {
        public UserActor {
            Objects.requireNonNull(userId);
            permissions = Set.copyOf(permissions);
        }
        public boolean has(String permission) { return permissions.contains(permission); }
    }

    /** An operation no person performs. process names the job, e.g. "seat-hold-expiry", "payment-reconciliation". */
    record SystemActor(String process) implements Actor {
        public SystemActor {
            if (process == null || process.isBlank()) throw new IllegalArgumentException("process is required");
        }
    }

    static SystemActor system(String process) { return new SystemActor(process); }
}

// ---------- shared.event ----------
public interface OutboxMessage {            // §8.2
    String routingKey();
    String aggregateType();
    UUID aggregateId();
    int schemaVersion();
    Instant occurredAt();
}

// ---------- shared.messaging ----------
public class NonRetryableMessageException extends RuntimeException {
    public NonRetryableMessageException(String message) { super(message); }
    public NonRetryableMessageException(String message, Throwable cause) { super(message, cause); }
}

// ---------- shared.application.port.out ----------
public interface EventPublisher { void publish(OutboxMessage message); }
public interface MessageBroker  { void publishConfirmed(PendingPublication publication); } // used only by RelayPendingEvents;
                                                                                            // returns only after a positive publisher confirm, else throws
/** One event_publications row as the relay sees it. */
public record PendingPublication(UUID eventId, String routingKey, String payloadJson, String correlationId) {}
public interface AuditTrail     { void record(AuditEntry entry); }                          // §12

public record AuditEntry(Actor actor, String action, String targetType, UUID targetId,
                         Instant occurredAt, Map<String, Object> metadata) {
    public AuditEntry { metadata = Map.copyOf(metadata); }
    public static AuditEntry of(Actor actor, String action, String targetType, UUID targetId, Instant occurredAt) {
        return new AuditEntry(actor, action, targetType, targetId, occurredAt, Map.of());
    }
    public AuditEntry withMetadata(Map<String, Object> metadata) {
        return new AuditEntry(actor, action, targetType, targetId, occurredAt, metadata);
    }
}
/** audit_events.action values. WORKSHOP_CANCELLED and ROLE_PERMISSIONS_CHANGED are fixed by database-schema.md §8.3; add new ones here. */
public final class AuditActions {
    public static final String WORKSHOP_CREATED         = "WORKSHOP_CREATED";
    public static final String WORKSHOP_CANCELLED       = "WORKSHOP_CANCELLED";
    public static final String WORKSHOP_RESCHEDULED     = "WORKSHOP_RESCHEDULED";
    public static final String REGISTRATION_CREATED     = "REGISTRATION_CREATED";
    public static final String REGISTRATION_EXPIRED     = "REGISTRATION_EXPIRED";
    public static final String ROLE_PERMISSIONS_CHANGED = "ROLE_PERMISSIONS_CHANGED";
    private AuditActions() {}
}
/** audit_events.target_type values. WORKSHOP, PAYMENT, ROLE are fixed by database-schema.md §8.3. */
public final class AuditTargets {
    public static final String WORKSHOP = "WORKSHOP", REGISTRATION = "REGISTRATION", PAYMENT = "PAYMENT",
                               ATTENDANCE = "ATTENDANCE", ROLE = "ROLE";
    private AuditTargets() {}
}

public interface IdempotencyStore {                                     // §21 — signatures and rules there
    Optional<IdempotencyRecord> find(IdempotencyScope scope, UserId actor, String key);
    void begin(IdempotencyScope scope, UserId actor, String key, String requestFingerprint, UUID resourceId);
    void complete(IdempotencyScope scope, UserId actor, String key, Object result, UUID resourceId);
    void completeOpenFor(IdempotencyScope scope, UUID resourceId, Object result);
    void abandonOpenFor(IdempotencyScope scope, UUID resourceId);
}
public sealed interface IdempotencyRecord { /* InProgress(fingerprint) | Completed(fingerprint, StoredResult) — §21 */ }
public enum IdempotencyScope { REGISTRATION_CREATE, PAYMENT_CHARGE }    // database-schema.md §8.1
public final class IdempotencyKeyInUseException extends DomainException {
    public IdempotencyKeyInUseException(IdempotencyScope scope) {
        super("A request with this Idempotency-Key is already being processed for " + scope);
    }
    @Override public ProblemKind kind() { return ProblemKind.CONFLICT; }
}
public final class IdempotencyKeyReusedException extends DomainException {   // same key, different body
    public IdempotencyKeyReusedException(IdempotencyScope scope) {
        super("This Idempotency-Key was already used with a different request body for " + scope);
    }
    @Override public ProblemKind kind() { return ProblemKind.UNPROCESSABLE; }
}

// ---------- shared.application.routing ----------
@Target({ElementType.METHOD, ElementType.TYPE}) @Retention(RetentionPolicy.RUNTIME)
public @interface ReadFromPrimary {}                                     // §20

// ---------- shared.web ----------
public final class CurrentActor {
    /** Builds the UserActor from the JWT "sub" and "permissions" claims (auth-and-identity.md). */
    public static Actor.UserActor from(Jwt jwt) { ... }
}
public final class CorrelationIds {
    /** The current OpenTelemetry trace ID (32 hex chars), read from the MDC key "correlationId" (observability.md §1). */
    public static String current() { ... }
}
public final class Problems {
    /** A ProblemDetail response for a sealed-Result variant (§17, §25.3); fills instance and correlationId.
     *  `type` is one of `ProblemTypes` (§17), or `about:blank` when the client need not branch. */
    public static ResponseEntity<ProblemDetail> of(HttpStatus status, URI type, String detail, HttpServletRequest request) { ... }
    public static ResponseEntity<ProblemDetail> conflict(HttpServletRequest request, URI type, String detail) {
        return of(HttpStatus.CONFLICT, type, detail, request);
    }
}
```

Module-specific helpers named in this document — `RegistrationPolicy` (holds `seatHoldDuration`, built from `RegistrationProperties` in `infrastructure/config`), `SeatLock`, `TicketSigner`, `SeatCounter` — belong to their module, not to `shared`. Their signatures are given where they are introduced (§19, §25).

---

## 14. Naming & Ubiquitous Language

### 14.1 Class names

| Element | Convention | Example |
|---|---|---|
| Aggregate / value object | Noun | `Workshop`, `Registration`, `Version` |
| Domain event | Past tense | `SeatReserved`, `RegistrationConfirmed`, `WorkshopCancelled` |
| Inbound port | `<Verb><Noun>UseCase` | `ReserveSeatUseCase` |
| Use case implementation | Port name minus `UseCase` — never `*Service` | `ReserveSeat` |
| Transaction helper of a use case (§19.2) | `<UseCase>Transaction` / `<UseCase>Steps`, package-private | `ReserveSeatTransaction`, `ChargePaymentSteps` |
| Implementation of a provider `api` port | `<Module><Port>` | `WorkshopSeatInventory implements SeatInventory` |
| Command / Result | `<UseCase>Command` / `<UseCase>Result` | `ReserveSeatCommand`, `ReserveSeatResult` |
| Outbound port | Role noun, no `Client`/`I` prefix or suffix | `WorkshopRepository`, `SeatLock`, `TicketSigner`, `PaymentGateway`, `BrochureStorage` |
| Read-model port | `<Noun>Queries` | `WorkshopCatalogQueries` |
| Query input | `<Verb><Noun>Criteria` | `SearchWorkshopsCriteria` |
| Driven adapter | `<Tech><Port>` | `JpaWorkshopRepository`, `RedissonSeatLock`, `StripePaymentGateway`, `OutboxEventPublisher` |
| Spring Data interface | `SpringData<Noun>Repository`, package-private | `SpringDataWorkshopRepository` |
| JPA entity | `<Noun>JpaEntity`, package-private | `WorkshopJpaEntity` |
| Listener / scheduler | `<Trigger>Listener` / `<Job>Scheduler` | `SeatHoldExpiryListener`, `NightlySyncScheduler` |
| Integration Event | `<Fact>IntegrationEvent` | `RegistrationConfirmedIntegrationEvent` |
| Deferred Job payload | `<Job>Requested` | `SeatHoldExpiryRequested` |
| Web DTO | Exactly the OpenAPI schema name | `CreateRegistrationRequest`, `Registration` |
| Mapper | `<Noun><Boundary>Mapper` | `WorkshopPersistenceMapper`, `WorkshopWebMapper` |

### 14.2 Ubiquitous language — cited, never invented

Names that already exist in the schema, the contract or the message topology are copied here, never renamed. For the seat-reservation concept:

| Concept | Canonical name | Source |
|---|---|---|
| The entity | Registration (`registrations`) | `architecture.md` §6.1, `database-schema.md` §4.1 |
| Status of a held, unpaid seat | `RESERVED` | `registrations.status` CHECK, `RegistrationStatus` in the API contract |
| Other statuses | `CONFIRMED`, `CANCELLED`, `EXPIRED` | same |
| The 15-minute window | "hold" — only as a noun for the window: `hold_expires_at`, `SeatHoldExpiryRequested`, `unihub.registration.hold.*` | `registration.md` §1/§3, `messaging.md` §2 |
| The act of taking a seat | "reserve" — `ReserveSeat`, `SeatReserved` | this document |
| Ticket confirmed message | `unihub.registration.ticket.confirmed` | `messaging.md` §2 |
| Idempotency scopes | `REGISTRATION_CREATE`, `PAYMENT_CHARGE` | `database-schema.md` §8.1 |

To change any of these, first change the source (migration, contract, or `messaging.md`), then this table.

---

## 15. Dependency Injection & Bean Naming

- Constructor injection only; no field or setter injection anywhere. Every class can be built with `new` and Mockito mocks in a unit test.
- Use case, adapter, listener and controller classes are package-private where possible; only port interfaces, `api` types, commands and results need to be public.
- Class names like `ReserveSeat` can repeat across modules. To make default bean names collision-proof, the application uses fully qualified bean names:
  ```java
  @SpringBootApplication(nameGenerator = FullyQualifiedAnnotationBeanNameGenerator.class)
  public class UniHubApplication { ... }
  ```

---

## 16. Time

- `domain` never reads a clock. Methods take `Instant` parameters.
- `application` reads "now" once per use case from an injected `java.time.Clock` (`Clock.systemUTC()` in production, `Clock.fixed(...)` in tests — `mocking-guidelines.md` §3) and passes that value down.
- Durations (seat-hold length, timeouts) come from `@ConfigurationProperties` (§22). Where a duration is also fixed in infrastructure — the seat-hold delay queue's `x-message-ttl` (`registration.md` §3) — both values must equal the same documented setting. A test asserts they match.

---

## 17. Errors — RFC 7807 Problem Details

Every non-2xx response is `application/problem+json` exactly as `packages/api-contract/common/error.yaml` defines it: `type`, `title`, `status`, `detail`, `instance`, `correlationId`. No module defines its own error envelope; Spring's `ProblemDetail` is the only error body type.

```java
// shared/domain/DomainException.java
public abstract class DomainException extends RuntimeException {
    protected DomainException(String message) { super(message); }
    public abstract ProblemKind kind();
}

// shared/domain/ProblemKind.java — the only mapping from business semantics to HTTP lives in shared.web
public enum ProblemKind { INVALID_REQUEST, FORBIDDEN, NOT_FOUND, CONFLICT, UNPROCESSABLE, UNAVAILABLE }
```

```java
// shared/web/GlobalProblemHandler.java
@RestControllerAdvice
class GlobalProblemHandler extends ResponseEntityExceptionHandler {   // also turns @Valid failures into 400 ProblemDetail

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ProblemDetail> handle(DomainException ex, HttpServletRequest request) {
        var status = switch (ex.kind()) {
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND       -> HttpStatus.NOT_FOUND;
            case CONFLICT        -> HttpStatus.CONFLICT;
            case UNPROCESSABLE   -> HttpStatus.UNPROCESSABLE_ENTITY;
            case UNAVAILABLE     -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        var body = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        body.setInstance(URI.create(request.getRequestURI()));
        body.setProperty("correlationId", CorrelationIds.current());
        var response = ResponseEntity.status(status);
        if (ex instanceof RetryableUnavailable r) {
            response.header(HttpHeaders.RETRY_AFTER, String.valueOf(r.retryAfter().toSeconds()));
        }
        return response.body(body);
    }
}
```

- The single advice lives in `shared.web` and maps by `ProblemKind`, so it never imports a module's exception class. `shared` stays independent of modules (§13), and `domain` stays unaware of HTTP.
- `401` and `403` come from Spring Security, not the advice. Configure the `AuthenticationEntryPoint` and `AccessDeniedHandler` to write the same `ProblemDetail` body; otherwise those two statuses fail contract-diff.
- A business outcome that is part of a sealed Result (sold out, §25) is **not** an exception. The controller maps that variant to a `ProblemDetail` itself.
- **Problem `type` per client-branching reason (checklist D8, Q6).** Every error a client must branch on carries its own `type` URI; the set is a stable contract (adding a value is a contract change, renaming or removing one is breaking). Everything else keeps `about:blank`.

  | `type` | Status | Raised by |
  |---|---|---|
  | `urn:unihub:problem:sold-out` | `409` | `createRegistration` — no seat left |
  | `urn:unihub:problem:already-registered` | `409` | `createRegistration` — the caller already holds an active registration |
  | `urn:unihub:problem:registration-not-open` | `409` | `createRegistration` — workshop not `PUBLISHED` |
  | `urn:unihub:problem:registration-not-reserved` | `409` | `chargePayment` — the registration is no longer `RESERVED` |
  | `urn:unihub:problem:payment-in-progress` | `409` | `chargePayment` — the registration already has a live payment |
  | `urn:unihub:problem:idempotency-key-in-use` | `409` | either — the same key is still being processed (retryable) |
  | `urn:unihub:problem:idempotency-key-reused` | `422` | either — the same key with a different body |

  Slugs name a **business condition**, never an HTTP status, and a slug once used is **never reused** for another meaning. Clients must handle an unknown `type` by falling back to the HTTP status, which is what keeps adding a `type` non-breaking.

  The constants live in `shared.web` next to the advice; a Result variant maps to its `type` in the controller, a `DomainException` declares it alongside its `ProblemKind`. A replayed Result carries the same `type`.
- Status semantics: `400` malformed request; `401` missing/invalid/expired token; `403` valid token without the permission; `404` not found, or hidden from this caller; `409` conflict with current state (including an `Idempotency-Key` still being processed); `422` an `Idempotency-Key` reused with a different request body (§21 rule 4); `503` + `Retry-After` for transient contention or an unavailable dependency. `UNPROCESSABLE` is added to the shared-kernel enum with the first Phase 2 code (Phase 2 checklist C26); `INVALID_REQUEST` already exists in code. `FORBIDDEN` (`403`) is for a caller who holds the permission but not the resource, e.g. paying for another student's registration (Phase 2 checklist 3.5); a missing permission stays a `403` from the security layer.

---

## 18. Validation

| Concern | Layer | Mechanism | Result |
|---|---|---|---|
| Request shape (required, size, format) | `web/dto` | Bean Validation + `@Valid` | `400` Problem Detail |
| Command preconditions | `application/command` | Compact constructor of the record | `IllegalArgumentException` = programming error at the mapper → `500`; the web layer must have rejected bad input first |
| Business rules | `domain` (or use case for Tier B) | `DomainException` subclass, or a sealed Result variant when the outcome must be recorded (§21) | `404` / `409` / `503` |

---

## 19. Transactions, Locking & Concurrency

### 19.1 What may happen inside a transaction

| Allowed inside `@Transactional` | Never inside `@Transactional` |
|---|---|
| Local DB reads and writes, including the outbox insert and the idempotency insert | HTTP calls to third parties (Stripe, AI summarization, notification providers, S3) |
| In-process calls to another module's `api` (§7 types 1–2) | Publishing to RabbitMQ, or waiting for a publisher confirm |
| `auditTrail.record(...)` — it defers itself to after commit (§12) | Acquiring or waiting on a Redis lock |

`@Transactional` goes on use case methods (or their `*Transaction` helper), never on controllers, listeners, schedulers or repository adapters.

### 19.2 Lock outside, transaction inside

A distributed lock is acquired **before** the transaction opens and released **after** it commits. Doing it inside the transaction holds a pooled connection for the whole lock wait. Worse, a `finally { unlock }` inside the transactional method runs *before* the proxy commits, letting the next caller in against uncommitted state.

The pattern is two beans: an entry use case (no `@Transactional`) owns the lock and calls a separate `*Transaction` bean that owns the transaction. Calling through an injected bean also avoids self-invocation (§19.3). The full code is in §25.

```java
// registration/application/port/out/SeatLock.java (checklist D4, Q11)
public interface SeatLock {
    /**
     * Acquires the per-workshop seat lock (key, lease and wait time: registration.md §2), waiting at most
     * waitTimeout. The caller acquires it BEFORE the transaction opens and closes the handle AFTER the
     * transaction has committed or rolled back (try-with-resources around the *Transaction call, §25.1).
     * @throws SeatLockUnavailableException (UNAVAILABLE, RetryableUnavailable) on timeout or, from Phase 3,
     *         when Redis is unreachable — never proceed without the lock
     */
    SeatLockHandle tryAcquire(WorkshopId workshopId, Duration waitTimeout, Duration leaseTime);
}
public interface SeatLockHandle extends AutoCloseable { @Override void close(); }
```

Adapters:

- **Phase 2 — `InProcessSeatLock`** (test code only since Phase 3, checklist D21): one `ReentrantLock` per `workshopId` (no global lock), `tryLock(waitTimeout)`, and entries removed when no thread holds or waits for them, so the map does not grow without bound. `leaseTime` is ignored — a JVM lock has no lease. Acquire and release happen on the same request thread. It does **not** protect across instances; the database check (version + `seats_taken <= capacity`) is the deciding layer, recorded in `test-matrix.md` §4.5 with the adapter.
- **Phase 3 — `RedissonSeatLock`** (parameters from `registration.md` §2): contention timeout **or** Redis unreachable → `SeatLockUnavailableException`; never proceed without the lock (INV-08, M-34). `unlock()` failing because the lease already expired (`IllegalMonitorStateException`) is caught and logged at `WARN`: by then the transaction has committed and the database's version check already protected it.
- Exhausted seat-claim retries after lost version races are a different `503`: `SeatTemporarilyUnavailableException` (§25.1). Both map to `UNAVAILABLE` + `Retry-After` and are never recorded under the idempotency key (§21 rule 7).

### 19.3 Self-invocation

A `@Transactional` method called through `this.` bypasses the Spring proxy and runs with no transaction. Transactional work that must be called from another method of the same use case goes into a separate injected bean. There are no exceptions to this rule.

### 19.4 Synchronous external calls — orchestrator

When a request must call a third party synchronously (the Stripe charge, `payment.md` §8). The charge is split so that **only one call can move money** — the confirm (Phase 2 checklist D16):

```
ChargePayment (no transaction)
  ├─ admission.admitting()            looks at the circuit, takes no permission; refused → 503, nothing written
  ├─ steps.begin(command)             @Transactional: idempotency find/begin(IN_PROGRESS, resourceId = payment id)
  │                                   + payments row PENDING, commit
  ├─ gateway.create(confirm=false)    no transaction; moves no money; key pi-create:{paymentId}
  ├─ steps.attach(paymentId, gatewayId)  @Transactional: store gateway_transaction_id, commit
  ├─ gateway.confirm(gatewayId)       no transaction; THE money-moving call; key pi-confirm:{paymentId},
  │                                   error_on_requires_action=true
  ├─ steps.complete(outcome)          @Transactional: payment updated with version check + idempotency complete
  └─ steps.abandon(paymentId)         @Transactional: PENDING → CANCELLED + idempotency abandonOpenFor; respond 503
```

The pre-flight question only *looks* at the circuit and keeps no permission (Phase 2 checklist 3.7): each gateway call asks the breaker for its own, so a permission taken here as well and never returned would leak a probe slot of a half-open breaker on every charge. The circuit may open between the question and the call; `abandon` covers that. Breaker, retry and time limiter wrap the `PaymentGateway` port in one adapter, so the orchestrator sees only port exceptions: a call the breaker refused or the time limiter cut off is `GatewayCallFailedException` for `create`, `GatewayNotReachedException` (refused) or `GatewayOutcomeUnknownException` (timed out — it may have been sent) for `confirm`.

**Which step runs (checklist D10, D16 — one rule for this path and for reconciliation, `payment.md` §6):**

| What happened | Next step | Response |
|---|---|---|
| `create` refused the payment method token itself (`PaymentMethodRejectedException`) | `complete` with `FAILED`: nothing was charged and the same token can never succeed | `201` with `FAILED`; a same-key retry replays it, a new attempt needs a new key and token (checklist D16 amendment 2026-10-21) |
| Any other failure in `create` — including a read timeout — or in `attach` | `abandon`: no money can have moved (nothing was confirmed) | `503`, nothing recorded; a same-key retry executes for real |
| `confirm` never left UniHub — breaker refusal, connect timeout, connection refused, DNS failure | `abandon` | `503`, same |
| `confirm` sent, outcome unknown — read timeout, reset after sending, gateway `5xx`, **any error answer that is not a decline** (`400`, `401`, `409`, `429`), a status nobody expects — on **any** attempt of the retry chain | `complete` with the Result "initiated": payment stays `PENDING`, key `COMPLETED` | `201` with `PENDING`; a same-key retry replays it, confirm is never sent again; webhook/reconciliation decide |
| `confirm` answered — `succeeded`, or declined (`requires_payment_method`) | `complete` with `SUCCEEDED` (+ outbox `unihub.payment.charge.succeeded` **in the same transaction**, `payment.md` §9) or `FAILED` | `201` with that status |
| `confirm` answered `processing` | `complete` with "initiated" (`PENDING`) | `201` with `PENDING` |

Rules for every gateway call:

- **Idempotency key per payment and per step** (`pi-create:`, `pi-confirm:`, `pi-cancel:` + payment id), reused by every retry of that step — SDK or Resilience4j. A step is never re-sent with a different key, and an unresolved one is re-sent only within Stripe's 24-hour key window; after that it is only retrieved.
- **SDK retries are configured explicitly**, never left to the SDK's default (Stripe: "Retry defaults depend on the SDK, version, and how you construct the client"); on this synchronous path they are `0` so the `TimeLimiter` stays the only clock. "Reached the gateway" is judged over the whole retry chain: if any attempt may have been sent, the step counts as reached.
- `metadata.payment_id` is set on create for operator investigation only — no code path resolves a payment from it.

The `201` body is a snapshot taken when `complete` committed; `getPayment` is the source of truth. Only a crash between `begin` and `complete` leaves the key `IN_PROGRESS` for reconciliation (`completeOpenFor`, §21 rule 7). The breaker permission acquired before `begin` is either consumed by the calls or released, so a `HALF_OPEN` probe slot is never leaked. Each call has its own 3s `TimeLimiter` and ≤3s HTTP timeouts; the whole request is ≈ 6.5s worst case, and the breaker counts calls, not charges (`payment.md` §2, Phase 2 checklist C37).

`begin`, `attach`, `complete` and `abandon` live on a separate `ChargePaymentSteps` bean. `TransactionTemplate` is **not** used in `application` — it sits outside the Spring allow-list (§3), and the two-bean shape already solves the problem.

### 19.5 Cross-module commands use `MANDATORY`

```java
// workshop/application/port/out/SeatCounter.java — implemented by JdbcSeatCounter in infrastructure/out/persistence
public interface SeatCounter {
    /** SELECT status, is_paid, seats_taken, capacity, version FROM workshops WHERE id = ? — on the Master. */
    Optional<SeatSnapshot> read(WorkshopId workshopId);
    /**
     * UPDATE workshops SET seats_taken = seats_taken + delta, version = version + 1
     *   WHERE id = ? AND version = :expected   (registration.md §2/§3)
     * @throws OptimisticConflictException when 0 rows are updated
     */
    void adjust(WorkshopId workshopId, int delta, Version expected);
}
public record SeatSnapshot(WorkshopStatus status, boolean paid, int seatsTaken, int capacity, Version version) {}
```

```java
// workshop/application/usecase/WorkshopSeatInventory.java
@Service
class WorkshopSeatInventory implements SeatInventory {
    private final SeatCounter seatCounter;

    WorkshopSeatInventory(SeatCounter seatCounter) { this.seatCounter = seatCounter; }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public SeatClaimOutcome claim(WorkshopId workshopId) {
        var seats = seatCounter.read(workshopId).orElseThrow(() -> new WorkshopNotFoundException(workshopId));
        if (seats.status() != WorkshopStatus.PUBLISHED) return new SeatClaimOutcome.NotOpenForRegistration();
        if (seats.seatsTaken() >= seats.capacity())     return new SeatClaimOutcome.SoldOut();
        seatCounter.adjust(workshopId, +1, seats.version());   // CHECK constraint is the final backstop
        return new SeatClaimOutcome.Claimed(seats.paid());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(WorkshopId workshopId) {
        var seats = seatCounter.read(workshopId).orElseThrow(() -> new WorkshopNotFoundException(workshopId));
        seatCounter.adjust(workshopId, -1, seats.version());
    }
}
```

`WorkshopStatus` is `DRAFT` / `PUBLISHED` / `CANCELLED` / `COMPLETED` and `is_paid` is a column of `workshops` (`database-schema.md` §3.1). Only a `PUBLISHED` workshop accepts registrations; a reschedule keeps it `PUBLISHED`.

```java
// workshop/api/SeatInventory.java
public interface SeatInventory {
    /** Must run inside the caller's transaction. Throws OptimisticConflictException on a lost version race. */
    SeatClaimOutcome claim(WorkshopId workshopId);
    /** Must run inside the caller's transaction. Throws OptimisticConflictException on a lost version race. */
    void release(WorkshopId workshopId);
}

// workshop/api/SeatClaimOutcome.java
public sealed interface SeatClaimOutcome {
    record Claimed(boolean paid) implements SeatClaimOutcome {}   // server-side isPaid, never from the client
    record SoldOut() implements SeatClaimOutcome {}
    record NotOpenForRegistration() implements SeatClaimOutcome {} // not PUBLISHED, cancelled, completed
}
```

`MANDATORY` turns "someone called `claim()` without an enclosing transaction" into an immediate error, rather than a silent separate transaction that would break the same-transaction invariant.

**Lost version race.** Under the lock, a version mismatch only happens when the lease expired mid-transaction (`registration.md` §2 trade-off). It surfaces as `OptimisticConflictException` and rolls back — **including** the idempotency insert, which is correct: the outcome was not decided. The entry use case translates it into `503 + Retry-After`, the same response as lock contention.

---

## 20. Read/Write Routing — CQRS-lite & Master/Replica

Implements `architecture.md` §4.1.

- **Query use cases** (catalog, search, reports, dashboards) skip aggregates entirely. A `<Noun>Queries` port returns view records straight from an SQL/JPQL projection.
- **Routing rule:**

  | Transaction | Routed to |
  |---|---|
  | `@Transactional(readOnly = true)` | Replica |
  | `@Transactional(readOnly = true)` + `@ReadFromPrimary` | Master |
  | Any read-write transaction | Master |

- Use `@ReadFromPrimary` for consistency-sensitive reads: idempotency lookups, and read-after-own-write views such as an organizer reloading a workshop right after saving it (`architecture.md` §4.1).
- Consumers re-checking state (§8.7) run in read-write transactions, so they read the Master.
- When the node depends on **who is asking** (a workshop manager reads their own write, a browsing student may read the Replica), the entry point is not `@Transactional`; it picks between two beans, one `@Transactional(readOnly = true)` and one `@Transactional(readOnly = true) @ReadFromPrimary` (§25.1, the same two-bean shape as `ReserveSeat`). `@ReadFromPrimary` on a method inside a transaction that has already started does nothing. Example: `GetWorkshop` → `GetWorkshopFromPrimary` / `GetWorkshopFromCache`.
- **Implementation note** (`shared.infrastructure`): an `AbstractRoutingDataSource` wrapped in a `LazyConnectionDataSourceProxy`. Without the lazy proxy, a connection is taken before the transaction's read-only flag is set, and every query goes to the Master.

```java
@Transactional(readOnly = true)
public SearchWorkshopsResult search(SearchWorkshopsCriteria criteria) {
    return SearchWorkshopsResult.of(catalog.search(criteria));   // WorkshopCatalogQueries → Replica
}
```

---

## 21. Idempotency

Implements `architecture.md` §4.5 and `database-schema.md` §8.1 (target shape after migration `V3`, `database-schema.md` §10.1). Decisions: Phase 2 checklist D1, C2, G1, G2 (2026-10-13/14).

```java
// shared/application/port/out/IdempotencyStore.java — every write needs the caller's transaction (MANDATORY);
// find is @ReadFromPrimary @Transactional(readOnly = true): it runs before the entry point's transaction (§25.1)
public interface IdempotencyStore {
    /** Consistency-sensitive: always reads the Master. Empty when no row exists. */
    Optional<IdempotencyRecord> find(IdempotencyScope scope, UserId actor, String key);

    /** Rebuilds the Result a StoredResult was serialized from: the variant of the sealed `resultType` whose simple
     *  name is `stored.type()`. The adapter that serialized it decodes it (application has no JSON library, §3). */
    <R> R decode(StoredResult stored, Class<R> resultType);

    /** Inserts IN_PROGRESS. DuplicateKeyException → IdempotencyKeyInUseException (CONFLICT). */
    void begin(IdempotencyScope scope, UserId actor, String key, String requestFingerprint, UUID resourceId);

    /** IN_PROGRESS → COMPLETED with the serialized Result, same transaction as the business write. */
    void complete(IdempotencyScope scope, UserId actor, String key, Object result, UUID resourceId);

    /** Reconciliation (G2): completes the open key of a resolved charge, if one exists. */
    void completeOpenFor(IdempotencyScope scope, UUID resourceId, Object result);

    /** Compensation (G1b, D16): deletes the open key of a charge that provably moved no money. */
    void abandonOpenFor(IdempotencyScope scope, UUID resourceId);
}

public sealed interface IdempotencyRecord {
    record InProgress(String requestFingerprint) implements IdempotencyRecord {}
    record Completed(String requestFingerprint, StoredResult result) implements IdempotencyRecord {}
}
// StoredResult(String type, String body): `type` = simple name of the sealed Result variant, `body` = its JSON.
// shared cannot import a module's Result, so IdempotentReplay (§25.1) passes the sealed type to store.decode.
// IdempotencyScope values: REGISTRATION_CREATE, PAYMENT_CHARGE (database-schema.md §8.1)
```

Rules:

1. The store records the **use case Result**, serialized (`result_type` + `result_body`), not an HTTP response. `application` does not know HTTP. Because the web layer's Result → HTTP mapping is deterministic and fixed by tests, a replay produces the identical HTTP response; changing that mapping is a breaking change.
2. The key row is written in the **same transaction** as the business effect it guards — never alone:
   - **Single-transaction operations** (`REGISTRATION_CREATE`): `begin` is the **first** statement of the business transaction and `complete` the last. The `IN_PROGRESS` row never commits on its own. A concurrent request with the same key blocks on the unique index; if the first commits it gets `IdempotencyKeyInUseException`, if the first rolls back it proceeds.
   - **Orchestrated operations with an external call** (`PAYMENT_CHARGE`, §19.4): `begin` commits atomically with the `PENDING` `payments` row (the row is the effect of that transaction, so the key still never exists alone) and carries the payment id as `resourceId`; `complete` runs in the second transaction. A same-key request in between sees `InProgress` → `409`; the gateway is never dialled twice.
3. **The actor is the authenticated user** (`Actor.UserActor` built from the verified JWT `sub`, §11, `auth-and-identity.md` §2), never a value from the request body or a header. The key is unique per `(scope, actor, key)` (`database-schema.md` §8.1).
4. **Fingerprint.** The web layer computes the request fingerprint (lowercase hex SHA-256 of the body in canonical JSON) and passes it in the command. On replay, `Completed` or `InProgress` with a **different** fingerprint → `IdempotencyKeyReusedException` (`ProblemKind.UNPROCESSABLE`, `422`); nothing executes. Same fingerprint: `Completed` → return its Result; `InProgress` → `IdempotencyKeyInUseException` (`409`).
5. Every outcome that must replay identically — including business rejections such as sold-out, already-registered, or "a payment for this registration is already in progress" — is a **variant of the sealed Result**, never a thrown exception. A thrown exception rolls back the transaction and the key row with it.
6. The adapter writes with `JdbcTemplate`, so a unique-constraint violation surfaces immediately inside `begin`, not later at flush, and translates `DuplicateKeyException` into `IdempotencyKeyInUseException` (`CONFLICT`). The same "catch the duplicate, translate it" rule applies to any **partial** unique index that guards a business invariant (e.g. one live payment per registration, `database-schema.md` §5.1): a partial index cannot be an `ON CONFLICT ON CONSTRAINT` target, so the adapter catches the error and the use case returns the matching Result variant — never a `500`.
7. Transient failures (lock contention, lost version race, dependency down, breaker refusal) are **not** recorded. They roll back, and the retry executes for real. For the orchestrated charge the single rule — shared by the synchronous path and reconciliation (§19.4, `payment.md` §6, checklist D16) — is: **provably no money moved** (the gateway id was never stored, the confirm never left UniHub, or a cancel succeeded) → payment `CANCELLED` and the key **deleted** (`abandonOpenFor`), so a same-key retry executes for real; **outcome known** (`SUCCEEDED`, `FAILED`, or "initiated" = `PENDING` after a confirm that was sent) → the key is completed with that Result, so a retry replays it and the confirm is never sent twice. Only a crash between the transactions leaves the key `InProgress`; reconciliation then applies the same rule (`completeOpenFor` / `abandonOpenFor`, G2).
8. The cleanup sweep deletes only `COMPLETED` rows older than the retry window (by `completed_at`); it never deletes an `IN_PROGRESS` row.

---

## 22. Configuration & Secrets

Every environment-dependent or tunable value is a `@ConfigurationProperties` record in the module's `infrastructure/config`, bound from environment, ConfigMap or Secret — never a literal in code (Gitleaks, M-13). `domain` and `application` never see a property source; they receive resolved values through constructor arguments.

```java
@ConfigurationProperties(prefix = "unihub.registration")
public record RegistrationProperties(Duration seatHoldDuration) {}
```

---

## 23. Logging & Correlation

- SLF4J, one `private static final Logger` per class; no `System.out` or `System.err`.
- `INFO` for business state transitions, `WARN` for expected or recoverable failures (lock timeout, audit publish failure, late unlock), `ERROR` for unexpected ones.
- Never log a JWT, password, signing key, full payment payload or QR payload.
- The correlation ID is the OpenTelemetry trace ID (`observability.md` §1). It reaches the MDC automatically; do not generate another one.
- On RabbitMQ the correlation ID travels in the `correlationId` message header, set once by a global `RabbitTemplate` post-processor and restored into the MDC by a shared listener advice (`observability.md` §1). Do not add header code in individual publishers or listeners.
- The relay restores each outbox row's stored correlation ID into the MDC before publishing it (§8.2), so a message consumed minutes later still links back to the originating request.
- Every relay publish and every listener invocation logs `eventId` (AMQP `message-id`), routing key and correlation ID at `INFO`.
- A scheduled trigger runs its cycle through `JobCycle.run("<job>", …)` (`shared.infrastructure.in.scheduling`), so the cycle has a trace and therefore a correlation ID in the MDC; a trigger that does not leaves the outbox rows it writes with no `correlation_id`. `CorrelationIdFilter` sets the ID on an HTTP request and `CorrelationIdProblemAdvice` puts it in every problem body; neither is called from a use case or a controller (`observability.md` §1).

---

## 24. Null-Safety & Immutability

- `Optional<T>` only as a return type, never as a field or parameter.
- Commands, Results, DTOs, value objects, events and port records are immutable records.
- Aggregates expose behavior methods, never public setters. Every Tier A aggregate carries `Version`.
- Collections returned from any layer are unmodifiable (`List.copyOf`, `Set.copyOf`).

---

## 25. Worked Example — Seat Reservation End-to-End

Sources: `registration.md` §1–§4, `database-schema.md` §4.1/§8.1, `checkin.md` §4, `architecture.md` §4.2/§4.5/§5.5.

### 25.1 Result, port-in and entry point

```java
// registration/application/result/ReserveSeatResult.java
public sealed interface ReserveSeatResult {
    record Reserved(RegistrationId id, Instant holdExpiresAt) implements ReserveSeatResult {}  // paid → RESERVED
    record Confirmed(RegistrationId id) implements ReserveSeatResult {}                         // free → CONFIRMED
    record SoldOut() implements ReserveSeatResult {}
    record NotOpenForRegistration() implements ReserveSeatResult {}
}

// registration/application/port/in/ReserveSeatUseCase.java
public interface ReserveSeatUseCase {
    ReserveSeatResult reserve(ReserveSeatCommand command);
}
// ReserveSeatCommand(Actor.UserActor actor, WorkshopId workshopId, String idempotencyKey, String requestFingerprint)
// IdempotentReplay.of(store, scope, actor, key, fingerprint, ResultType.class) (shared.application.usecase): Completed + same fingerprint → result; different fingerprint →
// IdempotencyKeyReusedException; InProgress → IdempotencyKeyInUseException (§21 rule 4)
```

```java
// registration/application/usecase/ReserveSeat.java — entry point, deliberately NOT @Transactional
@Service
class ReserveSeat implements ReserveSeatUseCase {
    private final IdempotencyStore idempotency;
    private final SeatLock seatLock;
    private final ReserveSeatTransaction transaction;
    private final RegistrationPolicy policy;

    ReserveSeat(IdempotencyStore idempotency, SeatLock seatLock, ReserveSeatTransaction transaction,
                RegistrationPolicy policy) {
        this.idempotency = idempotency; this.seatLock = seatLock; this.transaction = transaction; this.policy = policy;
    }

    @Override
    public ReserveSeatResult reserve(ReserveSeatCommand command) {
        // Fast replay path: a retried request never needs Redis (registration.md §4: "do not re-run … at all")
        var replay = IdempotentReplay.of(idempotency, IdempotencyScope.REGISTRATION_CREATE, command.actor().userId(),
                                         command.idempotencyKey(), command.requestFingerprint(), ReserveSeatResult.class);
        if (replay.isPresent()) {                // Completed + same fingerprint; mismatch → 422, InProgress → 409 (§21)
            return replay.get();
        }
        // lock → transaction opens → commit → lock released (§19.2). tryAcquire throws
        // SeatLockUnavailableException (UNAVAILABLE → 503 + Retry-After) when the wait times out.
        try (var held = seatLock.tryAcquire(command.workshopId(), policy.lockWait(), policy.lockLease())) {
            for (int attempt = 1; ; attempt++) {
                try {
                    return transaction.execute(command);           // a new transaction per attempt
                } catch (OptimisticConflictException lostRace) {   // e.g. an organizer edit bumped the version
                    if (attempt == policy.seatClaimAttempts()) {   // 3 (database-schema.md §3.1) — then 503,
                        throw new SeatTemporarilyUnavailableException(command.workshopId(), lostRace); // never 409
                    }
                }
            }
        }
    }
}
```

### 25.2 The transaction

Registration-owned collaborators used below:

```java
// registration/application/port/out/TicketSigner.java — implemented by EcdsaTicketSigner (infrastructure/out/crypto)
public interface TicketSigner {
    /** Generates the ticket code and signs {ticket_code, workshop_id, iat} with ES256 (checkin.md §4). */
    IssuedTicket issue(RegistrationId registrationId, WorkshopId workshopId, Instant issuedAt);
}
public record IssuedTicket(String ticketCode, String qrPayload) {}   // registrations.ticket_code / qr_payload

// registration/application/usecase/RegistrationPolicy.java — plain value, built in infrastructure/config
public record RegistrationPolicy(Duration seatHoldDuration, Duration lockWait, Duration lockLease,
                                 int seatClaimAttempts) {}           // from RegistrationProperties (§22): 15m, 2s, 5s, 3

// registration/application/port/out/SeatLock.java — Phase 2: InProcessSeatLock; Phase 3: RedissonSeatLock (fail-closed)
public interface SeatLock {
    /** Blocks up to waitTimeout. Throws SeatLockUnavailableException (UNAVAILABLE) on timeout or, from Phase 3,
     *  when Redis is unreachable. The in-process adapter ignores leaseTime (a JVM lock has no lease). */
    SeatLockHandle tryAcquire(WorkshopId workshopId, Duration waitTimeout, Duration leaseTime);
}
public interface SeatLockHandle extends AutoCloseable { @Override void close(); }   // release — after commit
```

```java
// registration/application/usecase/ReserveSeatTransaction.java
@Service
class ReserveSeatTransaction {
    private final IdempotencyStore idempotency;
    private final SeatInventory seatInventory;           // workshop.api — type 2 (§7), MANDATORY
    private final RegistrationRepository registrations;
    private final TicketSigner ticketSigner;             // type 3 (§7) — Registration signs (checkin.md §4)
    private final EventPublisher eventPublisher;
    private final AuditTrail auditTrail;
    private final RegistrationPolicy policy;             // seat-hold duration from RegistrationProperties
    private final Clock clock;

    // constructor omitted

    @Transactional
    public ReserveSeatResult execute(ReserveSeatCommand command) {
        // Backstop re-check inside the transaction: a duplicate may have committed while we waited for the lock
        var replay = IdempotentReplay.of(idempotency, IdempotencyScope.REGISTRATION_CREATE, command.actor().userId(),
                                         command.idempotencyKey(), command.requestFingerprint(), ReserveSeatResult.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        // First write of the transaction (§21 rule 2): a concurrent same-key request blocks here, then gets 409
        idempotency.begin(IdempotencyScope.REGISTRATION_CREATE, command.actor().userId(),
                          command.idempotencyKey(), command.requestFingerprint(), null);

        var now = clock.instant();
        ReserveSeatResult result = switch (seatInventory.claim(command.workshopId())) {
            case SeatClaimOutcome.SoldOut s -> new ReserveSeatResult.SoldOut();
            case SeatClaimOutcome.NotOpenForRegistration n -> new ReserveSeatResult.NotOpenForRegistration();
            case SeatClaimOutcome.Claimed claimed -> {
                var id = RegistrationId.newId();
                var registration = claimed.paid()
                    ? Registration.reserve(id, command.workshopId(), command.actor().userId(),
                                           now, policy.seatHoldDuration())                   // RESERVED
                    : Registration.confirmFree(id, command.workshopId(), command.actor().userId(),
                                               ticketSigner.issue(id, command.workshopId(), now), now); // CONFIRMED
                registrations.save(registration);
                registration.pullEvents().stream()
                    .flatMap(event -> RegistrationOutboxTranslator.translate(event).stream())
                    .forEach(eventPublisher::publish);   // paid: hold-delay Deferred Job; free: ticket.confirmed
                auditTrail.record(AuditEntry.of(command.actor(), AuditActions.REGISTRATION_CREATED,
                                                AuditTargets.REGISTRATION, id.value(), now));
                yield registration.isConfirmed()
                    ? new ReserveSeatResult.Confirmed(id)
                    : new ReserveSeatResult.Reserved(id, registration.holdExpiresAt());
            }
        };

        // Every decided outcome is recorded, including SoldOut (registration.md §4 "Why a 409 also gets recorded")
        idempotency.complete(IdempotencyScope.REGISTRATION_CREATE, command.actor().userId(),
                             command.idempotencyKey(), result, ReserveSeatResults.registrationIdOrNull(result));
        return result;
    }
}
```

**What each part guarantees:**

| Line | Invariant it upholds |
|---|---|
| Lock in `ReserveSeat`, transaction in `ReserveSeatTransaction` | Lock is advisory and wraps the whole commit (`architecture.md` §4.2) |
| `seatInventory.claim` under `MANDATORY` | `registrations` insert and `workshops.seats_taken`/`version` update commit together (`registration.md` §1) |
| DB check constraint + version (in Workshop's `SeatCounter` adapter) | The final accept/reject decision (`architecture.md` §4.2, §6.1) |
| `begin` first, `SoldOut` as a Result variant, then `complete` | A retry replays the same `409`, never re-evaluates (`registration.md` §4) |
| `eventPublisher.publish` inside the transaction | Outbox row commits with the business write (`architecture.md` §6.1) |
| `auditTrail.record` | Audited after commit, fail-open (`architecture.md` §5.5) |

### 25.3 Web mapping

```java
// registration/web/controller/RegistrationController.java (excerpt)
@PostMapping("/api/v1/registrations")
ResponseEntity<?> create(@Valid @RequestBody CreateRegistrationRequest request,
                         @RequestHeader("Idempotency-Key") String idempotencyKey,
                         @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
    var result = reserveSeat.reserve(RegistrationWebMapper.toCommand(request, idempotencyKey, CurrentActor.from(jwt)));
    return switch (result) {
        case ReserveSeatResult.Reserved r  -> ResponseEntity.status(201).body(RegistrationWebMapper.toResponse(r));
        case ReserveSeatResult.Confirmed c -> ResponseEntity.status(201).body(RegistrationWebMapper.toResponse(c));
        case ReserveSeatResult.SoldOut s   -> Problems.conflict(http, ProblemTypes.SOLD_OUT, "Workshop is sold out.");
        case ReserveSeatResult.NotOpenForRegistration n -> Problems.conflict(http, ProblemTypes.REGISTRATION_NOT_OPEN, "Workshop is not open for registration.");
    };
}
```

The permission annotation for this endpoint uses whichever permission code `auth-and-identity.md` assigns to student registration. Status codes and bodies follow `packages/api-contract/paths/registration.yaml`.

### 25.4 The expiry path uses the same building blocks

`ExpireSeatHold` (entry, lock) → `ExpireSeatHoldTransaction` (`@Transactional`): load the registration from the Master; if the status is not `RESERVED`, return (redelivery no-op); otherwise `registration.expire(now)`, save, `seatInventory.release(workshopId)`, and `auditTrail.record(...)` with `Actor.system("seat-hold-expiry")`. The listener that triggers it is shown in §8.5.

---

## 26. Testability & Structural Enforcement

### 26.1 What a unit test proves — and what it cannot

Use cases, domain objects, translators and mappers are unit-testable with plain constructors and Mockito (no Spring context, `unit-testing.md` §4). Unit tests assert on **decisions** — which Result variant, which exception, which messages were handed to `EventPublisher` — not on the internal sequence of calls.

| Needs a real runtime | Why a unit test can't prove it | Where it is proven |
|---|---|---|
| Lock-then-transaction ordering; `MANDATORY` propagation; rollback of the idempotency row on a lost race | Transactions exist only through Spring proxies | `*IT` with real Postgres + Redis (INV-02, INV-06/08) |
| Explicit version check in repository adapters | Needs Hibernate's persistence context | `*IT` concurrency test |
| Outbox → relay → broker → listener, redelivery no-op, parking-lot routing | Needs a real broker | `*IT` with RabbitMQ (INV-10, INV-11) |
| Replica routing and `@ReadFromPrimary` | Needs two datasources | `*IT` with a streaming replica (INV-03) |
| `@Valid`, `@PreAuthorize`, Problem Detail bodies, JSON shape | Needs the MVC and Security context | `*WebTest.java` `@WebMvcTest` slice, run by `./gradlew test` (`unit-testing.md` §4.1), plus contract-diff (M-17). End-to-end RBAC with a real JWT and real permission data stays `*IT` (M-01…M-03) |

### 26.2 ArchUnit — mandatory, runs in `./gradlew test`

This test is the executable form of §1, §3 and the "Rules at a Glance". It is a unit-level test (a structural check with no infrastructure), lives at `src/test/java/com/unihub/ArchitectureTest.java`, and is **the first test written in Phase 1** (evidence for M-12). It is written for ArchUnit 1.x with JUnit 5. The first CI run on the Phase 1 scaffold is what verifies it compiles and passes; after that, a red rule means the change is wrong, never the rule.

Two decisions make the rules unambiguous:
- `ANY` uses ArchUnit's alternation syntax `[a|b]` to match **business modules only**, so it never matches `com.unihub.shared`.
- Rules that compare "own module" with "other module" loop over the module list explicitly instead of relying on slice patterns.

```java
package com.unihub;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

@AnalyzeClasses(packages = "com.unihub", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final List<String> MODULES =
        List.of("user", "workshop", "registration", "payment", "checkin", "notification");

    /** Any business module. Never matches com.unihub.shared. */
    private static final String ANY = "com.unihub.[user|workshop|registration|payment|checkin|notification]";

    /**
     * Libraries only infrastructure.out (and infrastructure.config / shared.infrastructure) may use (§9.3).
     * Only the publishing side of Spring AMQP is listed: listeners in infrastructure.in legitimately use
     * @RabbitListener (org.springframework.amqp.rabbit.annotation).
     */
    private static final String[] DRIVEN_LIBRARIES = {
        "jakarta.persistence..", "org.hibernate..", "org.springframework.data..", "org.springframework.jdbc..",
        "org.springframework.amqp.rabbit.core..", "org.redisson..", "software.amazon..", "com.stripe..",
        "io.github.resilience4j..", "com.nimbusds..", "org.jose4j.."
    };

    // ---------- §1 Module boundary ----------

    @ArchTest
    static void modules_meet_only_through_api(JavaClasses classes) {
        for (String module : MODULES) {
            String own = "com.unihub." + module + "..";
            noClasses().that().resideInAPackage(own)
                .should().dependOnClassesThat(resideInAPackage(ANY + "..")
                    .and(not(resideInAPackage(own)))
                    .and(not(resideInAPackage(ANY + ".api.."))))
                .as("module '" + module + "' imports other modules only through their api package (§1)")
                .check(classes);
        }
    }

    @ArchTest static final ArchRule shared_is_independent_of_modules =
        noClasses().that().resideInAPackage("com.unihub.shared..")
            .should().dependOnClassesThat().resideInAPackage(ANY + "..")
            .as("shared never depends on a business module (§13)");

    // ---------- §3 Dependency rule, one rule per row ----------

    @ArchTest static final ArchRule domain_is_plain_java =
        classes().that().resideInAnyPackage(ANY + ".domain..", "com.unihub.shared.domain..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                "java..", ANY + ".domain..", "com.unihub.shared.domain..")
            .as("domain is plain Java (§3, §5)");

    @ArchTest static final ArchRule application_stays_framework_free =
        noClasses().that().resideInAPackage(ANY + ".application..")
            .should().dependOnClassesThat(
                resideInAnyPackage(ANY + ".web..", ANY + ".infrastructure..",
                                   "com.unihub.shared.web..", "com.unihub.shared.infrastructure..")
                .or(resideInAnyPackage(DRIVEN_LIBRARIES))
                .or(resideInAPackage("com.fasterxml.jackson.."))
                .or(resideInAPackage("org.springframework..")
                    .and(not(resideInAnyPackage("org.springframework.stereotype..",
                                                "org.springframework.transaction.annotation..")))))
            .as("application uses only its Spring allow-list (§3)");

    @ArchTest static final ArchRule api_is_a_pure_contract =
        classes().that().resideInAPackage(ANY + ".api..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                "java..", ANY + ".api..", "com.unihub.shared.domain..", "com.unihub.shared.event..")
            .as("api depends only on JDK, shared.domain and shared.event (§1, §3)");

    @ArchTest static final ArchRule web_sees_only_port_in_command_result =
        noClasses().that().resideInAPackage(ANY + ".web..")
            .should().dependOnClassesThat().resideInAnyPackage(
                ANY + ".domain..", ANY + ".application.port.out..", ANY + ".application.usecase..",
                ANY + ".application.event..", ANY + ".infrastructure..", "com.unihub.shared.infrastructure..")
            .as("web depends on port.in, command and result only (§3, §10)");

    @ArchTest static final ArchRule driving_adapters_call_only_port_in =
        noClasses().that().resideInAPackage(ANY + ".infrastructure.in..")
            .should().dependOnClassesThat().resideInAnyPackage(
                ANY + ".domain.model..", ANY + ".application.port.out..", ANY + ".application.usecase..",
                ANY + ".infrastructure.out..", ANY + ".web..")
            .orShould().dependOnClassesThat().resideInAnyPackage(DRIVEN_LIBRARIES)
            .as("listeners and schedulers call port.in only (§3, §8.5, §9.4)");

    @ArchTest static final ArchRule driven_adapters_never_drive =
        noClasses().that().resideInAPackage(ANY + ".infrastructure.out..")
            .should().dependOnClassesThat().resideInAnyPackage(
                ANY + ".application.port.in..", ANY + ".application.usecase..",
                ANY + ".infrastructure.in..", ANY + ".web..")
            .as("driven adapters implement port.out and never call use cases (§3, §9)");

    @ArchTest static final ArchRule driven_libraries_only_in_infrastructure_out =
        noClasses().that().resideOutsideOfPackages(
                ANY + ".infrastructure.out..", ANY + ".infrastructure.config..", "com.unihub.shared.infrastructure..")
            .should().dependOnClassesThat().resideInAnyPackage(DRIVEN_LIBRARIES)
            .as("persistence, messaging, Redis, cloud and crypto libraries only in infrastructure.out (§9.3)");

    // ---------- §19 Transactions ----------

    // containAnyMethodsThat exists only on ArchUnit's `.that()` (predicate) side, never on `.should()`,
    // so a single `.orShould().containAnyMethodsThat(...)` rule does not compile (§27 item 12). Split into
    // a class-level and a method-level rule over the same packages, preserving the full §19.1 intent.
    @ArchTest static final ArchRule transactional_only_in_application =
        noClasses().that().resideInAnyPackage(ANY + ".web..", ANY + ".infrastructure..", ANY + ".domain..", ANY + ".api..")
            .should().beAnnotatedWith(Transactional.class)
            .as("@Transactional only on application use cases — no class-level @Transactional here (§19.1)");

    @ArchTest static final ArchRule transactional_methods_only_in_application =
        noMethods().that().areDeclaredInClassesThat().resideInAnyPackage(
                ANY + ".web..", ANY + ".infrastructure..", ANY + ".domain..", ANY + ".api..")
            .should().beAnnotatedWith(Transactional.class)
            .as("@Transactional only on application use cases — no method-level @Transactional here (§19.1)");

    @ArchTest static final ArchRule no_jakarta_transactional =
        noClasses().should().dependOnClassesThat().haveFullyQualifiedName("jakarta.transaction.Transactional")
            .as("use Spring's @Transactional only (§19)");

    // ---------- §16 Time ----------

    @ArchTest static final ArchRule no_system_clock_reads =
        noClasses().that().resideOutsideOfPackage("com.unihub.shared.infrastructure..")
            .should().callMethod(Instant.class, "now")
            .orShould().callMethod(LocalDateTime.class, "now")
            .orShould().callMethod(LocalDate.class, "now")
            .orShould().callMethod(Clock.class, "systemUTC")
            .as("time comes from the injected Clock (§16)");

    // ---------- §8 Messaging ----------

    @ArchTest static final ArchRule no_reject_and_dont_requeue =
        noClasses().should().dependOnClassesThat()
            .haveFullyQualifiedName("org.springframework.amqp.AmqpRejectAndDontRequeueException")
            .as("use NonRetryableMessageException; reject-without-requeue drops messages here (§8.6)");

    // ---------- §6, §14, §15, §23 Conventions ----------

    @ArchTest static final ArchRule no_service_suffix_on_use_cases =
        noClasses().that().resideInAPackage(ANY + ".application.usecase..")
            .should().haveSimpleNameEndingWith("Service")
            .as("use case classes are named after their port, never *Service (§14.1)");

    @ArchTest static final ArchRule constructor_injection_only = NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
    @ArchTest static final ArchRule slf4j_only = NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;
    @ArchTest static final ArchRule no_system_out = NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
}
```

`com.unihub.shared` is intentionally excluded from the per-layer rules. It holds the cross-cutting machinery (the relay scheduler calls `RelayPendingEvents` directly, and the idempotency adapter carries `@Transactional` for routing, §20). Its only structural rule is independence from business modules. Review changes to `shared` more strictly for that reason.

**Role-name checks cannot be caught by ArchUnit.** `hasRole` appears inside SpEL strings in `@PreAuthorize`, not as a method call. The INV-01 static check is a Checkstyle rule on `src/main` (it runs in the `lint` job, `unit-testing.md` §2). `RegexpSingleline` is a `Checker`-level module, not a `TreeWalker` child:

```xml
<!-- config/checkstyle/checkstyle.xml, directly under <module name="Checker"> -->
<module name="RegexpSingleline">
  <property name="format" value="hasRole\(|hasAnyRole\(|&quot;ROLE_"/>
  <property name="message" value="Role-name checks are banned; use hasAuthority(Permissions.X) (INV-01, §11)"/>
  <property name="fileExtensions" value="java"/>
</module>
```

---

## 27. Open Items in Other Documents

These changes belong to the documents named, not to this one. Each is a precondition for the rule that cites it.

| # | Change | Document | Needed by |
|---|---|---|---|
| 1 | ✅ **Done (2026-10-14)** — `idempotency_keys` stores the use-case Result (`result_type` + `result_body`), keyed by `(scope, actor_user_id, idempotency_key)`, with `request_fingerprint`, `IN_PROGRESS`/`COMPLETED` and `resource_id`; specified as migration `V3` (`database-schema.md` §10.1, applied as `V3__phase2_idempotency_and_payments.sql`). `registration.md` §4, `payment.md` §8 and §21 here match | `database-schema.md` §8.1 | Phase 2 (M-20) |
| 2 | ✅ **Done** — JaCoCo `includes` = `com.unihub.*.application.usecase`, `com.unihub.*.domain.*`; `excludes` = `com.unihub.*.domain.exception`. Confirm on the scaffold that the report actually lists the usecase packages | `unit-testing.md` §3, `deployment-and-cicd.md` Coverage ADR + reference table | Phase 1 (M-15) |
| 3 | ✅ **Done** — `event_publications` gained `correlation_id VARCHAR(32) NULL` and `routing_key VARCHAR(100) NOT NULL` (the relay needs the key and must not derive it); the index text now matches the DDL. Log trail is continuous across the outbox; the Tempo trace is not (documented trade-off) | `database-schema.md` §8.2, `observability.md` §1 | Phase 1 (in the initial migration) |
| 4 | ✅ **Done** — single-instance relay per 1s cycle behind `unihub:lock:job:outbox-relay` (lease 30s), batch 200, per-message confirm, `mandatory = true`, no transaction across a publish; failing rows skipped and surfaced by `unihub_outbox_oldest_pending_age_seconds`. Claim-with-lease is the documented upgrade path | `messaging.md` §5, `distributed-coordination.md` §2/§5, `architecture.md` §4.6 | Phase 2 (INV-10); end-to-end in Phase 4 |
| 5 | **Done (2026-10-29, migration `V5`)** — `notifications`: add `source_event_id UUID NOT NULL` + `UNIQUE (source_event_id, user_id, channel)` and the send lease `claimed_at TIMESTAMPTZ NULL` (§8.5). The key first written here, `(source_event_id, channel)`, broke on fan-out (one event, N recipients, same channel) and was corrected (Phase 4 checklist D31, D33). Specified as migration `V5` (`database-schema.md` §7.1, §10.2; `notification.md` §3); applied as `V5__notification_dedup_claim_and_inbox.sql` (Phase 4 checklist 1.1) | `database-schema.md` §7.1, `notification.md` | Before the first notification consumer |
| 6 | ✅ **Done** — `audit_events.actor_type` (`USER`/`SYSTEM`) with `actor_user_id` NULL for `SYSTEM`, paired by a CHECK; the job name goes in `metadata.process`. `Actor` is now a sealed type (`UserActor`/`SystemActor`, §13.1). No seeded system user | `database-schema.md` §8.3, `auth-and-identity.md` | Phase 2 (first system-initiated audit: hold expiry) |
| 7 | ✅ **Done** — `*WebTest.java` web slices (`@WebMvcTest`, port/in mocked, no containers) run in `./gradlew test`; they don't replace the Integration-level RBAC tests M-01…M-03 | `unit-testing.md` §4.1, test `coding-standards.md` §1, `test-strategy.md` §3 | Phase 1 (first controller) |
| 8 | **Half done (2026-10-14).** "Payment succeeded → registration `CONFIRMED`": `unihub.payment.charge.succeeded` → `unihub.payment-confirmation.queue`, with the compensation `unihub.registration.confirmation.rejected` → `unihub.payment-refund.queue` (`messaging.md` §2). **Still open:** "workshop cancelled → cancel its registrations and release seats" (§7 type 4) — no Phase 2 matrix row needs it. Left out of Phase 4 by the owner (Phase 4 checklist D43). **Constraint for whoever builds it:** registrations cancelled because their workshop was cancelled must still be returned to Notification as recipients of the cancellation notice (`registration.md`, "Internal read for Notification"; `notification.md` §3), or those students are never told. **Left out of Phase 5 too (Phase 5 checklist D62);** Check-in does not need it, because reconciliation reads the workshop's status directly (`checkin.md` §7, D51): a scan of a registration whose workshop is cancelled is `REJECTED_INVALID` whether or not the registration was cancelled — whoever builds this keeps that result | `messaging.md` §2, `registration.md`, `payment.md` | M-19 (done half); open half: not yet scheduled |
| 9 | ✅ **Done (2026-10-14)** — `workshops.version` stays shared by seat claims and organizer edits; seat claim adds `seats_taken < capacity` to its `WHERE`, retries a lost version race internally (3 attempts) before `503`; organizer edits never write `seats_taken`; revisit thresholds for Phase 8 (`database-schema.md` §3.1, `registration.md` §2, `observability.md` §4) | `database-schema.md` §3.1, `registration.md` §2 | Phase 2 |
| 10 | ✅ **Done (2026-10-14)** — `REGISTRATION_CREATE`, seeded for `Student` by `V4`, guards `createRegistration` and `chargePayment` (§11, `database-schema.md` §10.1) | `auth-and-identity.md`, `user.md` | Phase 2 |
| 11 | ✅ **Done** — the correlation ID is a 32-hex-character trace ID (`observability.md` §1), not a UUID. The contract now declares it once as `headers.yaml#/CorrelationIdValue` (`pattern: '^[0-9a-f]{32}$'`), used by both `X-Correlation-Id` headers and `error.yaml`'s `correlationId`. Redocly lint passes | `packages/api-contract/common/headers.yaml`, `common/error.yaml` | Phase 1 (M-17) |
| 12 | ✅ **Done** — `ArchitectureTest`'s §19.1 rule as printed doesn't compile: `containAnyMethodsThat` exists only on `ClassesThat` (predicate side), never on `ClassesShould`/`.orShould()` (verified via `javac`, see commit/PR). Split into `transactional_only_in_application` (class-level `beAnnotatedWith`) + `transactional_methods_only_in_application` (`noMethods().that().areDeclaredInClassesThat()...should().beAnnotatedWith(...)`), same target packages, preserving full §19.1 intent at both class and method level. Rule count in §26.2 is now 18, not 17 — update the printed snippet to match. | `backend-coding-standards.md` §26.2 | Phase 1 (M-12) |

---

## 28. Reference Table

| Topic | Rule | Section |
|---|---|---|
| Module boundary | Only `<module>.api..` and `shared..` are importable across modules | §1 |
| Layout | `domain` / `application` / `api` / `web` / `infrastructure/{in,out,config}`; cross-cutting code only in `shared` | §2, §13 |
| Dependency rule | Package-based matrix, enforced by ArchUnit; `application` Spring allow-list = stereotype + transaction.annotation | §3, §26 |
| Domain | Plain Java, time passed in, sealed domain events, `Version` on every Tier A aggregate | §5 |
| Use cases | One per operation, no `*Service`; Result is a sealed type when there are several business outcomes | §6 |
| Interaction types | Read / same-transaction command (`MANDATORY`) / stateless computation / async reaction | §7 |
| Outbox | Exhaustive translator → `OutboxMessage` (routing key, aggregate, schema version); eventId and correlation ID are transport metadata | §8.1–§8.3 |
| Deferred Jobs | Self-addressed outbox messages, payload in `application/event` | §8.4 |
| Consumers | Listener → one `port/in`; re-check state; external effects deduplicated by event id; failures propagate; permanent → `NonRetryableMessageException` → parking-lot | §8.5–§8.7 |
| Persistence | Tier A separate JPA model + explicit version check; Tier B transaction script | §9 |
| Web | Names, paths, enums copied from `packages/api-contract` | §10 |
| RBAC | `@PreAuthorize(hasAuthority)` on controllers; data rules via `Actor` in use cases; no role names | §11 |
| Audit | Explicit port; after-commit, direct publish, never throws, metric on failure | §12 |
| Errors | RFC 7807 `ProblemDetail` only; one shared advice mapping `ProblemKind` | §17 |
| Transactions | No network/broker/lock wait inside; lock outside; two-bean pattern; orchestrator for sync third-party calls | §19 |
| Reads | `<Noun>Queries` projections; `readOnly` → Replica; `@ReadFromPrimary` → Master | §20 |
| Idempotency | Result recorded in the same transaction, keyed by scope + authenticated actor + key, fingerprint mismatch → `422`; rejections are Result variants; transients not recorded | §21 |
| Correlation | Trace ID = correlation ID; stored on the outbox row and republished | §23 |
