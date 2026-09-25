# Notification Module — Technical Design

This file is the physical-level completion of `architecture.md` §2.3/§4.3 (the Notification Module's consumer role in RabbitMQ Asynchronous Messaging Topology) and `docs/requirement.md` §1.3.2/§2.1. Those sources establish *that* the Notification Module is the only module allowed to call Notification Providers, *that* it consumes events asynchronously rather than being called synchronously by other modules, and — critically — *that* "new notification channels can be added in the future (e.g., SMS, Zalo, Telegram...) without affecting existing channels or requiring changes to other business modules of the system" (`docs/requirement.md` §1.3.2) and that "the notification module must be designed as pluggable: new notification channels can be added without modifying the core source code of other business modules" (`docs/requirement.md` §2.1). No prior document in this corpus specified the actual mechanism that makes that pluggability true — this file is that mechanism. Nothing here overrides `architecture.md` — where this file and that one appear to disagree, `architecture.md` is the authority and this file has a bug.

**Read this file top to bottom to understand the Notification module end to end.** Where it uses a shared platform mechanism (RabbitMQ), it explains what that mechanism gives Notification specifically and links out for the mechanism's own full design.

**Scope:** the `notifications` table this module owns, narrated at the rationale level (§1); the pluggable channel-provider architecture — the interface contract, the provider registry, per-channel failure isolation, and how a new channel is added — the design previously missing from this corpus (§2, the centerpiece of this file); lifecycle-event consumption from RabbitMQ and dispatch through the registry (§3); template rendering (§4); a consolidated failure-mode table and reference table (§5–§6).

**Out of scope, and where it actually lives:** the exact `notifications` column list (`../platform/database-schema.md` §7 — referenced throughout, not repeated here); the RabbitMQ broker/exchange/retry mechanism, reused here only for lifecycle-event delivery (`../platform/messaging.md`); async audit-event publishing, which shares this module's transport but belongs to no single business module (`../platform/messaging.md` §3); the JWT that authenticates the in-app notification inbox's own read endpoint (`../platform/auth-and-identity.md`).

---

## Table of Contents

- [Architecture Decision Record](#architecture-decision-record)
  - [ADR Mini: Notification Channel Pluggability Mechanism](#adr-mini-notification-channel-pluggability-mechanism)
- [1. Data Model — What This Module Owns](#1-data-model-what-this-module-owns)
- [2. Pluggable Channel Architecture](#2-pluggable-channel-architecture)
- [3. Lifecycle Events — Consumption & Dispatch](#3-lifecycle-events-consumption-dispatch)
- [4. Template Rendering](#4-template-rendering)
- [5. Failure Modes & Fail-Safe Behavior](#5-failure-modes-fail-safe-behavior)
- [6. Reference Table](#6-reference-table)
- [API Surface](#api-surface)

---

## Architecture Decision Record

### ADR Mini: Notification Channel Pluggability Mechanism

**Context:** `docs/requirement.md` §1.3.2 and §2.1 both require, in almost identical language, that new notification channels be addable "without affecting existing channels or requiring changes to other business modules" and "without modifying the core source code of other business modules." `architecture.md` §2.3's component diagram already establishes the *boundary* — the Notification Module is the sole caller of Notification Providers, consuming events asynchronously rather than being invoked synchronously — but a boundary alone doesn't make a *specific channel* pluggable; it only isolates *which module* talks to providers. Without a stated mechanism, "pluggable" is just a word in the requirements doc that an implementer has to invent an interpretation for.

**Decision:** a `NotificationChannel` interface, one implementation per channel (`EmailNotificationChannel`, `InAppNotificationChannel`, and so on for any future channel), each registered as a Spring bean and resolved at dispatch time from a `Map<String, NotificationChannel>` keyed by `notifications.channel` (Spring's standard pattern for collecting all beans of an interface into a name-keyed map — no custom registry class needs to be hand-rolled). The consumer that reacts to a lifecycle event (§3) never references a concrete channel class; it resolves `channel -> NotificationChannel` from that map and calls the interface method. Adding a channel is: implement the interface, register the bean, add the new value to `notifications.channel`'s CHECK constraint (`../platform/database-schema.md` §7.1, a tolerant migration per `architecture.md` §6.2) — nothing else in this module, and nothing in any *other* module, changes.

**Consequences:**
- The requirement's own wording — "without requiring changes to other business modules" — is satisfied structurally: Registration, Workshop, Payment, User, and Check-in publish domain events (`../platform/messaging.md` §2) and never know how many channels exist, what they're called, or whether a dispatch to one of them succeeded.
- "Without affecting existing channels" is satisfied because each channel implementation is independently instantiated and independently invoked (§2 below) — one channel's failure is isolated from another's by construction, not by a convention an implementer has to remember to follow.
- The interface is intentionally minimal (one method, `send(NotificationDispatch)`, §2) — every channel-specific detail (an SMTP client, a Zalo API token, a Telegram bot handle) lives entirely inside that channel's own implementation class, never leaking into the interface contract or the dispatch consumer.

**Trade-offs accepted:** a rules-engine / configuration-driven channel system (channels defined in a database table with a generic HTTP-webhook dispatch shape, no code per channel) was considered and rejected — most real notification providers (SMTP, a push-notification SDK, a messaging-platform API) have different enough request shapes, retry semantics, and authentication schemes that a fully generic webhook abstraction would either be too thin to be useful or would re-invent a large fraction of each provider's own SDK inside UniHub. A compiled interface with one implementation per channel is the simpler design for this project's scale, and is still exactly as pluggable as the requirement asks for — "pluggable" here means *isolated and addable without touching other code*, not *configurable without a deploy*, and the SRS's own language ("added in the future," not "toggled at runtime by an Organizer") supports that reading.

---

## 1. Data Model — What This Module Owns

**Rationale:** `architecture.md` §6.1 names `Notification` as this module's sole entity. Exact columns are in `../platform/database-schema.md` §7.1; this section narrates why the ones central to this file's later sections look the way they do.

- **`channel`** (`EMAIL`/`IN_APP`, extensible) — this column is the literal key the dispatch registry (§2) resolves against. Adding SMS/Zalo/Telegram is a new allowed value in this CHECK constraint — a tolerant migration per `architecture.md` §6.2 — which is exactly the "pluggable" property at the schema level, paired with the interface-level pluggability in §2.
- **`type`** (`REGISTRATION_CONFIRMED`/`WORKSHOP_CANCELLED`/`WORKSHOP_RESCHEDULED`) — drives which template (§4) is rendered for the notification's content.
- **`related_entity_type`/`related_entity_id`** — a deliberately un-enforced polymorphic reference (no FK — `../platform/database-schema.md` §7.1's own trade-off note), since the Notification Module only ever writes a row in reaction to an event it received from another module, never from unvalidated user input.
- **`status`** (`PENDING`/`SENT`/`FAILED`) — set by the dispatch consumer (§3) after calling the resolved channel's `send()`.

---

## 2. Pluggable Channel Architecture

**Rationale:** per the ADR above, this section is the design that was missing everywhere else in the corpus — the actual mechanism, not just the boundary, that makes `docs/requirement.md` §1.3.2/§2.1's pluggability requirement true.

**Model — the interface contract:**

```java
public interface NotificationChannel {

    /** The value of notifications.channel this implementation handles — e.g. "EMAIL". */
    String channelCode();

    /**
     * Attempts delivery. Never throws for an ordinary delivery failure (a bounced
     * email, an unreachable provider) — returns a result the dispatch consumer (§3)
     * uses to set notifications.status. Throwing is reserved for a genuinely
     * unexpected condition the consumer's own retry/dead-letter chain (§3,
     * ../platform/messaging.md §4) should handle like any other consumer failure.
     */
    NotificationDeliveryResult send(NotificationDispatch dispatch);
}

public record NotificationDispatch(
    UUID notificationId,
    UUID userId,
    String userEmail,        // resolved once by the consumer (§3), not re-fetched per channel
    NotificationType type,   // drives template selection, §4
    Map<String, Object> templateContext  // e.g. workshop title, new schedule time
) {}

public enum NotificationDeliveryResult { SENT, FAILED }
```

The interface is deliberately narrow: one method, a plain result enum, and a dispatch payload assembled once by the consumer — not by each channel. A channel implementation (`EmailNotificationChannel`, `InAppNotificationChannel`, a future `SmsNotificationChannel`) owns everything specific to itself — its provider SDK/client, its own auth credentials (injected per `architecture.md` §5.4, never hardcoded), its own request/response shape — entirely behind this one method. Nothing about SMTP, or a push-notification SDK, or a messaging-platform API ever appears in the interface itself.

**Model — registry & resolution:**

```java
@Component
public class NotificationDispatcher {

    private final Map<String, NotificationChannel> channelsByCode;

    // Spring collects every NotificationChannel bean automatically; this constructor
    // just re-keys the list by channelCode() once, at startup — no custom registry
    // class, no manual wiring per channel.
    public NotificationDispatcher(List<NotificationChannel> channels) {
        this.channelsByCode = channels.stream()
            .collect(Collectors.toMap(NotificationChannel::channelCode, c -> c));
    }

    public NotificationDeliveryResult dispatch(String channelCode, NotificationDispatch dispatch) {
        NotificationChannel channel = channelsByCode.get(channelCode);
        if (channel == null) {
            // notifications.channel's CHECK constraint (../platform/database-schema.md §7.1)
            // already prevents an unrecognized value reaching this point under normal
            // operation — this is a defensive guard against a schema/code drift (a CHECK
            // value added without the corresponding bean), not an expected runtime path.
            throw new IllegalStateException("No NotificationChannel registered for: " + channelCode);
        }
        return channel.send(dispatch);
    }
}
```

**Adding a new channel — the concrete, bounded set of changes:**

1. Implement `NotificationChannel` for the new provider (e.g. `SmsNotificationChannel`), register it as a Spring bean.
2. Add the new value to `notifications.channel`'s CHECK constraint (`../platform/database-schema.md` §7.1) — a tolerant, additive migration (`architecture.md` §6.2).
3. Add a template for that channel if its content format differs from an existing one (§4).

Nothing in Registration, Workshop, Payment, User, or Check-in changes — none of them reference a channel by name; they publish domain facts (`unihub.registration.ticket.confirmed`, etc., `../platform/messaging.md` §2) and the Notification Module alone decides which channel(s) a given notification type dispatches through, entirely inside this module's own configuration.

**Per-channel failure isolation:** each channel's `send()` call is independent — one channel implementation throwing or returning `FAILED` has no code path that affects another channel's own dispatch for the same or a different notification. If a future notification type fans out to multiple channels (e.g. both `EMAIL` and `IN_APP` for the same fact), each channel's own row/dispatch is resolved and sent independently; a Zalo outage, say, never blocks or fails an email that would otherwise have sent successfully.

**Guarantee:** this module satisfies `docs/requirement.md` §1.3.2/§2.1 literally — a new channel is one new class, one new bean registration, and one additive schema value; no other module's source is ever touched, and no existing channel's behavior changes as a side effect of adding one.

---

## 3. Lifecycle Events — Consumption & Dispatch

**Rationale:** `docs/requirement.md` §1.3.2/§2.2 requires students to be notified of registration confirmation and organizers' cancel/reschedule actions promptly, without the notification call's latency landing on the triggering request (`architecture.md` §4.3). This uses the shared RabbitMQ deployment described in `../platform/messaging.md` §1–§2; this section is the Notification-specific consumer that sits on top of the registry in §2.

**Model:** all three lifecycle facts — `unihub.registration.ticket.confirmed`, `unihub.workshop.event.cancelled`, `unihub.workshop.event.rescheduled` — route to the single `unihub.notification.queue` (`../platform/messaging.md` §2). The consumer:

1. Creates the corresponding `notifications` row (`../platform/database-schema.md` §7.1) — `status = 'PENDING'`.
2. Resolves the recipient's contact detail (e.g. email) and assembles a `NotificationDispatch` (§2).
3. Renders the notification's content via the template for its `type` (§4).
4. Calls `NotificationDispatcher.dispatch(notifications.channel, dispatch)` (§2).
5. Updates `notifications.status`/`sent_at` based on the result — `SENT` or `FAILED`.

The queue topology itself doesn't need to know about channels at all — routing a lifecycle event to `unihub.notification.queue` is entirely independent of which channel(s) that event's notification type ultimately dispatches through, which is exactly what makes a new channel addable later without touching `../platform/messaging.md`'s design either.

**Idempotency (redelivery-safety, distinct from `idempotency_keys`):** a redelivered lifecycle event must not send a duplicate email or create a duplicate in-app notification. The consumer checks for an existing `notifications` row matching `(user_id, related_entity_type, related_entity_id, type)` before creating and dispatching a new one — if found, it's a safe no-op. This is an application-level check, not a database unique constraint (`../platform/database-schema.md` §7.1 didn't add one, since a rare duplicate notification is a UX nuisance, not a correctness violation the way a duplicate charge would be — but the consumer still avoids it as ordinary good behavior, not just because it's forced to).

**Accepted trade-off — this check-then-insert has a small race under concurrent redelivery:** because there is no unique constraint behind it (`../platform/database-schema.md` §7.1), two redeliveries of the same message landing on two consumer threads/pods at nearly the same moment can both pass the "no existing row" check before either has inserted, producing two `notifications` rows for the same fact. This is accepted, not closed, for the same reason `../platform/database-schema.md` §7.1 chose not to add a unique constraint here: the failure mode is a duplicate email or duplicate in-app notification — a UX nuisance a student might shrug off, never a correctness violation the way a duplicate charge or duplicate seat would be. It is deliberately treated differently from `idempotency_keys` (`../platform/database-schema.md` §8.1), where the same race would be unacceptable and is closed with a database-enforced unique constraint instead of an application-level check alone.

**Guarantees:** every lifecycle fact reaches the Notification module at least once; redelivery produces at most one dispatched notification per `(user, entity, type)` tuple, under normal (non-racing) conditions. Retry/dead-lettering on consumer failure follows the generic mechanism in `../platform/messaging.md` §4, applied to `unihub.notification.queue`.

---

## 4. Template Rendering

**Rationale:** `notifications.type` (§1) drives content, but content itself — subject line, body copy, which `templateContext` fields get interpolated — is not specified anywhere else in this corpus. This section closes that gap at the level this file's scope calls for: the mechanism, not the literal copy (which is a product/content decision, not an architecture one).

**Model:** one template per `(type, channel)` pair — a `REGISTRATION_CONFIRMED` email and a `REGISTRATION_CONFIRMED` in-app notification are different templates, since an email has a subject line and richer formatting while an in-app notification is typically a short string. Templates are simple placeholder-substitution text (e.g. Java's `String.format`-style or a minimal templating library — not a full page-rendering engine, since a notification is a short message, not a rendered web page), populated from `NotificationDispatch.templateContext()` (§2) — workshop title, new schedule time, ticket code, and so on, depending on `type`.

**Where templates live:** as resources inside the Notification Module's own codebase, versioned alongside it — not in the database. A template is a code-level content decision (what a `WORKSHOP_RESCHEDULED` email says), not per-tenant configuration data this system needs to change without a deploy; nothing in `docs/requirement.md` asks for Organizer-editable notification copy.

**Guarantee:** rendering a notification's content is a pure function of `(type, channel, templateContext)` — the same inputs always produce the same content, and a missing `templateContext` key for a given `type` is a template/consumer mismatch caught in testing, not a runtime surprise a student sees as a broken notification.

---

## 5. Failure Modes & Fail-Safe Behavior

| Situation | Behavior | Why |
|---|---|---|
| A channel's `send()` returns `FAILED` (provider rejects, times out, etc.) | `notifications.status = 'FAILED'`; the consumer does **not** throw for an ordinary delivery failure (§2) | A bounced email or an unreachable provider is an expected, non-exceptional outcome — throwing here would trigger `../platform/messaging.md` §4's consumer-level retry chain for a failure that channel-level retry (if the channel implementation itself wants one) is better positioned to handle. |
| `NotificationDispatcher.dispatch()` is called with a `channelCode` that has no registered bean | Throws `IllegalStateException` — treated as an unexpected consumer failure, enters the standard retry/dead-letter chain (`../platform/messaging.md` §4) | Defensive: `notifications.channel`'s CHECK constraint (`../platform/database-schema.md` §7.1) should make this unreachable under normal operation; if it happens, it signals a schema/code drift worth a human looking at, not a routine retry candidate. |
| Redelivered lifecycle event for an already-dispatched notification | No-op under normal conditions; a small window under concurrent redelivery can produce a duplicate `notifications` row (§3) | Accepted trade-off — a duplicate notification is a UX nuisance, not a correctness violation (§3). |
| One channel (e.g. Zalo) is down while another (e.g. Email) is healthy | The healthy channel's dispatch is entirely unaffected (§2) | Per-channel isolation by construction — no shared failure path between channel implementations. |

---

## 6. Reference Table

Every interface, table, and mechanism decision in this document, in one place — the same purpose the closing tables in `../platform/database-schema.md` §11 and `../platform/messaging.md` §6 serve for their own documents.

| Item | Value | Backs |
|---|---|---|
| Channel abstraction | `NotificationChannel` interface, one implementation per channel, Spring-bean-collected into a `Map<String, NotificationChannel>` | `docs/requirement.md` §1.3.2/§2.1's pluggability requirement (§2) |
| Adding a channel | New implementation class + bean registration + one additive CHECK-constraint value | No other module touched; existing channels unaffected (§2) |
| Lifecycle-event queue | `unihub.notification.queue` (`../platform/messaging.md` §2) | Async dispatch off the triggering request's critical path (`architecture.md` §4.3) |
| Redelivery dedup | Application-level check on `(user_id, related_entity_type, related_entity_id, type)` — no DB unique constraint | Distinct, deliberately looser guarantee than `idempotency_keys` (`../platform/database-schema.md` §8.1) — duplicate notification is a UX nuisance, not a correctness violation (§3) |
| Template scope | One template per `(type, channel)` pair, code-resident | Notification content is a code-level decision, not runtime-configurable data (§4) |
| `notifications.channel` values | `EMAIL`, `IN_APP` (extensible via tolerant migration) | `../platform/database-schema.md` §7.1 |

---

## API Surface

The concrete HTTP contract for this module's one endpoint — the in-app notification inbox read, `listMyNotifications` — is specified in `../../../packages/api-contract/paths/notification.yaml` (schema in `../../../packages/api-contract/schemas/notification.yaml`), not repeated here. That endpoint's route is flagged there as inferred: this file confirms the inbox read endpoint's *existence* (the scope note above excludes "the JWT that authenticates" it, implying the endpoint itself exists) without ever naming its path. Two real gaps are also flagged there rather than invented around, both direct consequences of `../platform/database-schema.md` §7.1's column list as this file describes it: no rendered notification content is persisted anywhere (§4's template rendering happens once, at dispatch, and is never written back to the row), and there is no read/unread column, so no "mark as read" operation exists. See `../../../packages/api-contract/README.md` for the toolchain.
