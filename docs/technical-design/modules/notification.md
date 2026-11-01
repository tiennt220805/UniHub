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
- **`status`** (`PENDING`/`SENT`/`FAILED`) — set by the dispatch consumer (§3) after calling the resolved channel's `send()`. `PENDING` is also where a row stays when its channel was temporarily unavailable (§5): only a permanent outcome leaves `PENDING`.
- **`source_event_id`** (Phase 4 checklist D31) — the AMQP `message-id` of the lifecycle event that produced the row, which is the `event_publications.id` of the outbox row (`RabbitMessageBroker` sets it; the retry chain republishes the same `Message`, so it survives every hop). `UNIQUE (source_event_id, user_id, channel)` is the redelivery dedup key (§3): per *fact*, not per notification type.
- **`claimed_at`** (Phase 4 checklist D33) — a short send lease taken by an atomic conditional `UPDATE` before the provider is called, so two concurrent deliveries of the same message cannot both send the same row (§3).
- **`title`/`body`** (Phase 4 checklist D38) — the rendered in-app content, filled **only** for `IN_APP` rows, rendered once when the row is inserted (§3 step 1) and never re-rendered, so the inbox shows exactly what was sent even after a template changes. `EMAIL` rows leave them `NULL`: the email itself is the delivered copy.

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
     * uses to decide the row's fate (D32, §5). Throwing is reserved for a genuinely
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

public enum NotificationDeliveryResult { SENT, REJECTED, DEFERRED, UNAVAILABLE }
```

**Delivery results (Phase 4 checklist D32, scope split added 2026-10-28):** the result says whether trying again could help, which decides between a final state and a retry, and whether the failure belongs to **this recipient** or to **the whole channel**, which decides whether the rest of the fan-out keeps sending (§3).

| Result | Meaning | Row | Consumer |
|---|---|---|---|
| `SENT` | The provider accepted the message | `SENT`, `sent_at` set | Continues |
| `REJECTED` | A permanent refusal **for this one recipient**: for SMTP, a 5xx reply to that recipient (`RCPT TO`), e.g. 550 mailbox unavailable, 553 invalid address | `FAILED` | Continues; never retried |
| `DEFERRED` | A transient refusal **for this one recipient**: for SMTP, a 4xx reply to that recipient (`RCPT TO`), e.g. 450/451/452 greylisting or a full mailbox | Stays `PENDING`, claim released | Continues with the next row of the same channel; throws at the end of the pass (§3 step 3) |
| `UNAVAILABLE` | A failure of **the channel itself**, hitting every recipient alike: connection refused, timeout, a 4xx not tied to a recipient, **and** authentication/relay refusals (e.g. 535) even though they are 5xx | Stays `PENDING`, claim released | Skips this channel's remaining rows for this delivery (§3, Fan-out); throws at the end of the pass |

Classifying an auth or relay refusal as `REJECTED` would let one wrong SMTP credential mark every email `FAILED` for good, which is the very outage this split exists to survive. The `IN_APP` channel only writes to this module's own database, so it never returns `UNAVAILABLE`; a database failure there is an exception like any other. Because its content is already on the row (§1), "sending" an `IN_APP` row is marking it `SENT`.

**The `EMAIL` channel (Phase 4 checklist D36):** SMTP through Spring's `JavaMailSender` (`spring-boot-starter-mail`), vendor-neutral. Configuration `unihub.notification.smtp.*` (host, port, username, password, `from` — the sender address, `starttls`, and the three timeouts `connect-timeout`, `read-timeout`, `write-timeout`); credentials are runtime secrets (INV-18), never committed. `unihub.notification.smtp.host` is **required**: blank stops startup with a clear message, the same rule as `unihub.redis.address` (Phase 3 checklist D21), so a misconfigured environment cannot silently turn every email into `UNAVAILABLE`; an SMTP server that is configured but unreachable at runtime does not stop startup. Timeouts are bounded and set through **all three** Jakarta Mail properties, because each defaults to waiting forever: `mail.smtp.connectiontimeout` 5s, `mail.smtp.timeout` 10s, `mail.smtp.writetimeout` 10s. Mapping to the results above: `SMTPAddressFailedException` (a per-recipient reply code) → `REJECTED` for a 5xx code, `DEFERRED` for a 4xx one; authentication failures, connection failures, timeouts and any other failure → `UNAVAILABLE`. The read and write timeouts bound each socket operation, not a whole send: one SMTP transaction is several round trips (greeting, EHLO, AUTH, MAIL, RCPT, DATA, body, final reply). Tests never reach a real mail provider: local and Integration tests use **Mailpit** (an SMTP catcher with an HTTP API) as the test double (`docs/testing/guidelines/mocking-guidelines.md` §1–§2).

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

**A channel that throws (Phase 4 checklist D40):** the consumer catches an exception from `send()` per row and treats it as `UNAVAILABLE` for that channel — row stays `PENDING`, claim released, the channel's remaining rows skipped for this delivery, logged at `ERROR` — while every other channel keeps sending; the message is then retried at the end of the pass (§3) and, if the channel keeps throwing, parks. An exception therefore never cuts another channel's rows short, which is what the isolation below requires.

**Proof of pluggability (M-39, D40):** structural rules in `ArchitectureTest` (no class outside `com.unihub.notification..` depends on `NotificationChannel`/`NotificationDispatcher`; the dispatcher and consumer depend on no concrete channel class), plus an Integration test that registers a third, test-only channel (`TEST_PIGEON`) through `@TestConfiguration` with no change to `src/main`, on a database of its own whose Flyway run adds a test-only migration widening the `channel` CHECK — the same additive step a real new channel takes (step 2 below).

**Per-channel failure isolation:** each channel's `send()` call is independent — one channel implementation throwing or returning `REJECTED`/`UNAVAILABLE` has no code path that affects another channel's own dispatch for the same or a different notification. If a future notification type fans out to multiple channels (e.g. both `EMAIL` and `IN_APP` for the same fact), each channel's own row/dispatch is resolved and sent independently; a Zalo outage, say, never blocks or fails an email that would otherwise have sent successfully.

**Guarantee:** this module satisfies `docs/requirement.md` §1.3.2/§2.1 literally — a new channel is one new class, one new bean registration, and one additive schema value; no other module's source is ever touched, and no existing channel's behavior changes as a side effect of adding one.

---

## 3. Lifecycle Events — Consumption & Dispatch

**Rationale:** `docs/requirement.md` §1.3.2/§2.2 requires students to be notified of registration confirmation and organizers' cancel/reschedule actions promptly, without the notification call's latency landing on the triggering request (`architecture.md` §4.3). This uses the shared RabbitMQ deployment described in `../platform/messaging.md` §1–§2; this section is the Notification-specific consumer that sits on top of the registry in §2.

**Model:** all three lifecycle facts — `unihub.registration.ticket.confirmed`, `unihub.workshop.event.cancelled`, `unihub.workshop.event.rescheduled` — route to the single `unihub.notification.queue` (`../platform/messaging.md` §2). The consumer (Phase 4 checklist D31–D33; `backend-coding-standards.md` §8.5):

1. **Transaction 1 — insert.** Creates the `notifications` row(s) for the fact (`../platform/database-schema.md` §7.1), `status = 'PENDING'`, `source_event_id` = the message's `message-id`, `title`/`body` rendered for `IN_APP` rows (§1, §4), with `INSERT … ON CONFLICT DO NOTHING`. A message without a `message-id` is a permanent failure (`NonRetryableMessageException`, parking-lot).
2. **Per row still `PENDING` for this `source_event_id`, outside any transaction:**
   1. **Claim** it with one atomic statement: `UPDATE notifications SET claimed_at = now() WHERE id = ? AND status = 'PENDING' AND (claimed_at IS NULL OR claimed_at < now() - <lease>) RETURNING claimed_at`. Zero rows means another delivery holds or has finished it: skip it. The returned `claimed_at` is this delivery's claim token.
   2. Resolves the recipient's contact detail (e.g. email) and assembles a `NotificationDispatch` (§2); an `EMAIL` row's content is rendered here via the template for its `type` (§4), while an `IN_APP` row already carries its `title`/`body` from step 1.
   3. Calls `NotificationDispatcher.dispatch(notifications.channel, dispatch)` (§2).
   4. **Short transaction — mark.** `SENT` → `status = 'SENT'`, `sent_at`; `REJECTED` → `status = 'FAILED'`; `DEFERRED`/`UNAVAILABLE` → row stays `PENDING` and the claim is **released**: `SET claimed_at = NULL WHERE id = ? AND claimed_at = <this delivery's token>`, so the next retry hop can claim it at once, and an overrunning sender never clears a claim another delivery has since taken.
3. After the pass, if **any** row of this `source_event_id` is still `PENDING`, for any reason (`DEFERRED`, its channel was `UNAVAILABLE`, or it was skipped because another delivery holds a live claim on it), throw a transient exception so the message enters the retry chain (§5). Acking while a row is `PENDING` would lose that notification silently. A redelivery repeats steps 1–3: step 1 inserts nothing, step 2 only touches rows that are still `PENDING`. A concurrent duplicate therefore costs one retry hop and then finds the row `SENT`; a crashed holder's row is claimable again once its lease expires.

**Lease bounds:** the lease is configuration, `unihub.notification.claim-lease` (so a test on a shortened retry chain, `unihub.messaging.retry-delays`, shortens it too). It must be longer than the worst case of one send — the connect timeout plus one read/write timeout per SMTP round trip (≈ 8 round trips: 5s + 8 × 10s ≈ 85s), because the timeouts bound each socket operation, not the send (§2) — so a live sender never loses its claim; and well under the chain's cumulative delay (≈ 5.6 min with production delays), so a crashed holder's row becomes claimable again before the message's retries run out and it parks. Default: **120s**.

**Telling the three facts apart (Phase 4 checklist D34):** the listener receives the raw `Message` and picks the payload type from the AMQP `type` property, which the outbox relay sets to the row's routing key on every message (`../platform/messaging.md` §2). The routing key itself cannot be used: after one retry hop it is the fixed `unihub.notification.redelivery.requested`. A message without `type` (published before the property existed) falls back to the `x-original-routing-key` header, then to the received routing key; a type that still cannot be determined, or an unknown one, is a `NonRetryableMessageException` (straight to the parking-lot). This queue is the documented exception to `backend-coding-standards.md` §8.5's "the converter infers the type from the listener parameter".

**Where the content comes from (Phase 4 checklist D35):** the payloads carry ids only, and this module never reads another module's tables. It owns three driven ports in `notification.application.port.out` — `RecipientDirectory` (email and name by `userId`), `WorkshopDetails` (title, start/end time, location, status by `workshopId`) and `WorkshopRecipients` (the `userId`/`registrationId` pairs of a workshop's registrations) — implemented by adapters in `notification.infrastructure.out` that call read interfaces in `user.api`, `workshop.api` and `registration.api` (`backend-coding-standards.md` §7, interaction type 1: a read for display, the port owned by the consumer). None of those modules depends on Notification, so no cycle appears. Reads go to the **Master**: the consumer runs right after the write that produced the event.

- **Recipients of `WORKSHOP_CANCELLED`/`WORKSHOP_RESCHEDULED`:** registrations `CONFIRMED` **and** `RESERVED` (a student holding a seat while paying must learn of the change before paying); `EXPIRED` and `CANCELLED` are not notified. The list is read **when the message is consumed**, not when it was published: a student who registered between the reschedule and the consumption also gets the reschedule notice, which is a surplus notice, never a missing one. A cancelled workshop takes no new registrations, so there is no gap the other way. When "workshop cancelled → cancel its registrations" is built (`backend-coding-standards.md` §27 #8), registrations cancelled *because the workshop was cancelled* must stay in this list.
- **Out-of-order events** (`../platform/messaging.md` §5 promises no order): before notifying a `WORKSHOP_RESCHEDULED`, the consumer reads the workshop's current state and skips the event — a no-op that creates no row — if the workshop is `CANCELLED`, or if the event's `newStartTime`/`newEndTime` no longer match the workshop's current times (a newer reschedule exists, and its own event notifies). `WORKSHOP_CANCELLED` is always notified. The skip is a pure function of current state, so a redelivery skips again. If an earlier delivery of the now-stale event already inserted rows, its rows still `PENDING` are marked `FAILED` (superseded, logged) when it is skipped, so none is left `PENDING` for ever.
- **Missing data:** a `userId`/`workshopId`/`registrationId` that does not exist (in practice only a stale message whose data was deleted) is a `NonRetryableMessageException` for the single-recipient `REGISTRATION_CONFIRMED`, and for a missing workshop. Inside a fan-out, one recipient that cannot be resolved is skipped and logged, never the whole message: one bad row must not stop everyone else learning that a workshop was cancelled.

**Fan-out (Phase 4 checklist D39):** one message, one consumer, the two phases above. A cancellation of a 300-seat workshop is one message → 300 recipients × 2 channels = 600 rows inserted in step 1, sent one by one in step 2. Which channels a type uses is this module's own configuration, never the publisher's; today all three types → `EMAIL` + `IN_APP`.

- **One unavailable channel stops only itself for this delivery:** the first `UNAVAILABLE` (a channel-wide failure, §2) from a channel makes the consumer skip that channel's remaining rows for the rest of this delivery (they stay `PENDING`, unclaimed); every other channel still sends all its rows; step 3 then throws, and the retry hop resumes only the `PENDING` rows. A `DEFERRED` recipient never triggers this skip: it is that recipient's problem, so the channel carries on with the next row, otherwise one greylisted address — first in the stable row order on every retry — would stop every other email of the fan-out until the message parks. Without the skip, a **hung** SMTP server would cost one full send timeout per recipient (10s × 300 ≈ 50 min) inside one delivery, past the broker's `consumer_timeout`, and the broker would hand the message to another consumer to start over, indefinitely.
- **Listener container for this queue:** `concurrency` 2 and `prefetch` 1 per instance (configuration). With one thread, one large fan-out holds every later message on the queue — a confirmation email waits minutes behind a cancellation, against "promptly". Two threads let a confirmation go out while a fan-out runs; prefetch 1 is what makes that true, since a busy consumer would otherwise already hold up to the default 250 messages in its own buffer, queued behind the fan-out and ageing towards `consumer_timeout` themselves. Concurrent processing is safe by D31 (rows) and D33 (sends).
- **Limit:** a fan-out must finish one delivery within `consumer_timeout` of the pinned broker version. With a healthy provider at a few seconds per email this holds for workshop capacities of the order of hundreds (`proposal.md` §12). The upgrade path, should capacities grow, is two hops: the lifecycle consumer only inserts rows and writes one `notification.dispatch.requested` job per row to the outbox, and a second consumer sends exactly one row.

The queue topology itself doesn't need to know about channels at all — routing a lifecycle event to `unihub.notification.queue` is entirely independent of which channel(s) that event's notification type ultimately dispatches through, which is exactly what makes a new channel addable later without touching `../platform/messaging.md`'s design either.

**Idempotency (redelivery-safety, distinct from `idempotency_keys`) — Phase 4 checklist D31, D33.** Two different duplicates are closed by two different mechanisms:

- **Duplicate rows** are closed by the database: `UNIQUE (source_event_id, user_id, channel)` with `ON CONFLICT DO NOTHING`, so even two deliveries racing on two pods leave one row. The key is per *fact*: a broker redelivery or a relay double-publish carries the same `message-id` and is absorbed, while two reschedules of the same workshop are two outbox rows with two ids and correctly produce two notifications. (An earlier draft deduplicated on `(user_id, related_entity_type, related_entity_id, type)` with an application-level check and accepted the race; that key silently swallowed a second reschedule, leaving the student with the old time, so it was replaced.)
- **Duplicate sends** are closed by the claim (step 2.1). A unique key alone does not stop them: two concurrent deliveries both see the same `PENDING` row after step 1 and would both call the provider. This happens in normal operation, not only after a crash: a relay double-publish puts two copies on the queue for two pods, and a long fan-out past the broker's `consumer_timeout` is redelivered to another pod while the first is still sending. The lease bounds are above; holding a row lock across the send is not an option (`backend-coding-standards.md` §8.5: the provider is called outside any transaction), and a Redis lock would be advisory and add a Redis dependency this module does not otherwise have.

**Accepted trade-off — at-least-once towards the provider (D33):** a crash, or a lost database connection, after the provider accepted a message but before step 2.4 marks it leaves the row `PENDING` with an expired claim; the redelivery sends it **once more**. An SMTP timeout *after* the message data was transmitted is the same window without a crash: the channel reports `UNAVAILABLE`, yet the server may already have accepted the mail. Closing this window would need a two-phase commit with the mail server, which SMTP does not offer. The system-side effect (rows, states) stays a no-op under redelivery, which is what INV-11 requires; only the external provider sees at-least-once. This is a decision, recorded here and in the Phase 4 sign-off §5, not an assumption.

**Guarantees:** every lifecycle fact reaches the Notification module at least once; redelivery, sequential or concurrent, never creates a second row per `(source_event_id, user_id, channel)` and never calls a provider for a row that is already `SENT` or `FAILED`, or that another delivery holds a live claim on; each `(fact, user, channel)` is sent exactly once except in the crash window above. Retry/dead-lettering on consumer failure follows the generic mechanism in `../platform/messaging.md` §4, applied to `unihub.notification.queue`.

---

## 4. Template Rendering

**Rationale:** `notifications.type` (§1) drives content, but content itself — subject line, body copy, which `templateContext` fields get interpolated — is not specified anywhere else in this corpus. This section closes that gap at the level this file's scope calls for: the mechanism, not the literal copy (which is a product/content decision, not an architecture one).

**Model:** one template per `(type, channel)` pair — a `REGISTRATION_CONFIRMED` email and a `REGISTRATION_CONFIRMED` in-app notification are different templates, since an email has a subject line and richer formatting while an in-app notification is typically a short string. Templates are simple placeholder-substitution text (e.g. Java's `String.format`-style or a minimal templating library — not a full page-rendering engine, since a notification is a short message, not a rendered web page), populated from `NotificationDispatch.templateContext()` (§2) — workshop title, new schedule time, ticket code, and so on, depending on `type`.

**Where templates live:** as resources inside the Notification Module's own codebase, versioned alongside it — not in the database. A template is a code-level content decision (what a `WORKSHOP_RESCHEDULED` email says), not per-tenant configuration data this system needs to change without a deploy; nothing in `docs/requirement.md` asks for Organizer-editable notification copy.

**Phase 4 defaults (checklist D37):**

- **Language:** Vietnamese, matching the Web Application.
- **Format:** an email is plain text plus a minimal HTML alternative; an in-app notification is a `title` and a short `body` (§1). Placeholder substitution in code, no templating library. Every value interpolated into the HTML part is HTML-escaped (a workshop title is organizer input).
- **Length:** a rendered in-app `title` always fits `notifications.title` (`VARCHAR(200)`): the renderer shortens the interpolated workshop title (up to `VARCHAR(255)`, `../platform/database-schema.md` §3.1) with an ellipsis so the whole title fits. A value that does not fit would make step 1 of the fan-out fail on every attempt and park the message, notifying nobody.
- **Times:** payload times are `Instant` (UTC); templates format them in a configured display time zone, `Asia/Ho_Chi_Minh` by default, never in the server's zone.
- **Links:** "Đăng ký của tôi" links are built from a configured Web Application base URL (`unihub.web.base-url`), never hardcoded. They point at the Web routes `/me/registrations` (the list) and `/me/registrations/:id` (one registration's e-ticket page). **Fixed 2026-11-01 (Phase 5 checklist 6.1):** Phase 4 built the list link as `<base>/registrations`, a route the Web Application does not have; the Phase 4 tests pinned the same wrong path, so they were green while the link was dead.
- **The e-ticket:** `docs/requirement.md` §1.3.2 asks for the confirmation "with their e-ticket". The signed QR e-ticket is Phase 5 (`checkin.md` §3–§4, INV-12), so in Phase 4 the confirmation carries the workshop title, time, location, the registration reference and a link to "Đăng ký của tôi"; the QR is added to the `REGISTRATION_CONFIRMED` email template in Phase 5 (`checkin.md` §4). Recorded in the Phase 4 sign-off §5.
  - **As built (Phase 5 checklist 6.1, 2026-11-01):** `DispatchNotifications` reads the QR payload through its own `RegistrationTickets` port (adapter over `registration.api.RegistrationTickets`, at the Master) when it plans a confirmation and puts it in the template context under `ticketQrPayload`. `EmailNotificationChannel` draws it (`TicketQrImages`, ZXing core, level Q, PNG by `ImageIO`) and attaches it inline with `Content-ID` `ticket-qr`. `ResourceNotificationTemplates` writes the e-ticket block (`REGISTRATION_CONFIRMED.EMAIL.ticket.html.tpl` / `.ticket.text.tpl`: the `<img src="cid:ticket-qr">`, a link to `/me/registrations/:id`) into `{{ticketBlock}}` only when the context has a non-blank payload; the payload is never printed by any template, so rendering stays a pure function of `(type, channel, context)`. Without a ticket `{{ticketBlock}}` is empty and the email is what it was before Phase 5. A payload that cannot be drawn (empty, or too long for a QR code) fails the same way on every attempt, so the channel sends the email **without** the image and logs a warning without the payload, rather than returning `UNAVAILABLE` and walking the retry chain into the parking lot (owner agreed 2026-11-01). The payload is a credential: `NotificationDispatch` and the internal `Plan` print only their keys, and no stored `title`/`body` contains it. The in-app notification's stored text is unchanged; the Web inbox links a `REGISTRATION_CONFIRMED` item to its registration page (checklist 7.2), which also covers rows written before.
  - **Decided for Phase 5 (Phase 5 checklist D55):** the `REGISTRATION_CONFIRMED` email carries the QR as an inline PNG image (`Content-ID`) in the HTML part, rendered by this module with ZXing (`com.google.zxing:core`, a new dependency) from `qr_payload`, read through a `registration.api` interface at the Master; the text part and the in-app notification carry a link to the e-ticket page (`/me/registrations/:id`). A registration with no ticket (confirmed before Phase 5) gets the confirmation without the image, still `SENT`. In-app `title`/`body` already stored stay as they are.

**Guarantee:** rendering a notification's content is a pure function of `(type, channel, templateContext)` — the same inputs always produce the same content, and a missing `templateContext` key for a given `type` is a template/consumer mismatch caught in testing, not a runtime surprise a student sees as a broken notification.

---

## 5. Failure Modes & Fail-Safe Behavior

| Situation | Behavior | Why |
|---|---|---|
| A channel's `send()` returns `DEFERRED` (a transient refusal for this recipient, e.g. greylisting, §2) | Row stays `PENDING`, claim released; the channel goes on with the next recipient; the message is retried at the end of the pass | One recipient's temporary problem must not hold back the rest of a fan-out. |
| A channel's `send()` returns `REJECTED` (a permanent refusal for this recipient, §2) | `notifications.status = 'FAILED'`; not retried; the message's other rows are unaffected | Trying again cannot help; a bad address must not hold up anyone else's notification. |
| A channel's `send()` returns `UNAVAILABLE` (provider unreachable, timed out, 4xx, auth/relay refused, §2) | Row stays `PENDING` and its claim is released; after the message's other rows are processed the consumer throws a transient exception, so the message walks the 3-stage chain (5s → 30s → 5m, `../platform/messaging.md` §4). Each redelivery sends only rows still `PENDING` (§3) | `docs/requirement.md` §1.3.2 asks for prompt notification: a one-minute SMTP outage during a cancellation must not turn every email into a permanent `FAILED` (Phase 4 checklist D32). |
| The provider stays unavailable for longer than the chain (≈ 5.6 min) | The message goes to `unihub.parking-lot` under the ordinary 3-attempt rule (notification is **not** one of `../platform/messaging.md` §4's must-not-park exceptions: it holds neither money nor a seat). Rows stay `PENDING`. Signals: `unihub_notification_delivery_total{channel,result}` and the age of the oldest `PENDING` row (alerts in Phase 6) | Recovery is a manual replay of the parked message; by §3 it sends only the rows still `PENDING`, so a replay never re-sends a delivered notification. |
| SMTP **hangs** (accepts connections, never answers) during a large fan-out | The first email send times out (≤ 10s) → `UNAVAILABLE`; the remaining email rows of this delivery are skipped, in-app rows all go out, the message enters the retry chain (§3, Fan-out) | Bounds one delivery to about one send timeout instead of one per recipient, well inside the broker's `consumer_timeout` (D39). |
| A `WORKSHOP_RESCHEDULED` arrives after the workshop was cancelled, or after a newer reschedule | No-op: no row, no send (§3) | Messages carry no order; the student must not end up with a stale time (D35). |
| The event names a user, workshop or registration that no longer exists | Single-recipient confirmation or missing workshop → parking-lot at once (`NonRetryableMessageException`); one unresolvable recipient inside a fan-out → that recipient skipped and logged | Retrying cannot make deleted data reappear; one bad recipient must not block the others (D35). |
| A message whose `type` cannot be determined | Parking-lot at once (`NonRetryableMessageException`) | The payload cannot be read safely as any of the three facts (D34). |
| Crash after the provider accepted, before the row is marked | Row `PENDING`, claim expires; the redelivery sends once more | Accepted at-least-once window towards the provider (§3, D33). |
| `NotificationDispatcher.dispatch()` is called with a `channelCode` that has no registered bean | Throws `IllegalStateException` — treated as an unexpected consumer failure, enters the standard retry/dead-letter chain (`../platform/messaging.md` §4) | Defensive: `notifications.channel`'s CHECK constraint (`../platform/database-schema.md` §7.1) should make this unreachable under normal operation; if it happens, it signals a schema/code drift worth a human looking at, not a routine retry candidate. |
| Redelivered lifecycle event (sequential or concurrent) | No new row (unique key); rows already `SENT`/`FAILED` are skipped; a row under another delivery's live claim is skipped and the message is retried rather than acked while it is `PENDING` (§3) | INV-11: re-processing is a no-op inside the system (D31, D33). |
| One channel (e.g. Zalo) is down while another (e.g. Email) is healthy | The healthy channel's dispatch is entirely unaffected (§2) | Per-channel isolation by construction — no shared failure path between channel implementations. |

**As built (Phase 4 checklist Mục 2, 2026-10-29).** Tier B (backend-coding-standards.md §9.1): a transaction script, no aggregate. The `PENDING → SENT | FAILED` rule is the conditional `UPDATE … WHERE status = 'PENDING'` of each state change (`JdbcNotificationRepository`), not a domain class: the database is the one place concurrent deliveries meet, so that is where "never backwards" must hold. `domain/model` holds only `NotificationType` and `NotificationPolicy` (the lease and the channels per type, as plain values).

- **Ports** (`application/port/out`): `NotificationChannel`, `NotificationRepository`, `NotificationTemplates`, `NotificationMetrics`, `NotificationQueries` (the inbox read), and the three consumer-owned reads `RecipientDirectory`, `WorkshopDetails`, `WorkshopRecipients` (adapters in `infrastructure/out/reads` over `user.api.UserContacts`, `workshop.api.WorkshopSummaries`, `registration.api.WorkshopAttendees`).
- **Use cases:** `DispatchNotifications` (not `@Transactional`; every database step is a short transaction in the `NotificationRows` helper bean) and `ListMyNotifications`. `NotificationDispatcher` is the channel registry of §2 and names no concrete channel.
- **Channels** (`infrastructure/out/channel`): `EmailNotificationChannel` (+ `SmtpFailures`, the SMTP-failure table of §2) and `InAppNotificationChannel`. A malformed address and a user with no address are `REJECTED`: retrying cannot fix them and no other recipient is affected.
- **Configuration:** `unihub.notification.*` (`NotificationProperties`): `smtp.*` (host required), `claim-lease`, `display-zone`, `listener.{concurrency,prefetch}`, and `channels.<type>` (kebab-case type → list of channel codes; a type left out uses `EMAIL`, `IN_APP`). At start every configured code must have a `NotificationChannel` bean, or the application does not start. `unihub.web.base-url` builds the links.
- **Listener:** `NotificationEventListener` on a container factory of its own (`notificationListenerContainerFactory`: concurrency 2, prefetch 1). Boot attaches the shared failure handling (correlation id, republish to the retry chain) only to *its* default factory, so the factory sets that customizer by hand; `NotificationListenerContainerIT` proves a failed message reaches the retry stage and not nowhere.
- **Broker pin:** RabbitMQ is `3.13.7-management` in docker-compose and in the integration tests; its `consumer_timeout` is 1800000 ms (30 minutes), the bound a fan-out delivery must stay under (D39).

---

## 6. Reference Table

Every interface, table, and mechanism decision in this document, in one place — the same purpose the closing tables in `../platform/database-schema.md` §11 and `../platform/messaging.md` §6 serve for their own documents.

| Item | Value | Backs |
|---|---|---|
| Channel abstraction | `NotificationChannel` interface, one implementation per channel, Spring-bean-collected into a `Map<String, NotificationChannel>` | `docs/requirement.md` §1.3.2/§2.1's pluggability requirement (§2) |
| Adding a channel | New implementation class + bean registration + one additive CHECK-constraint value | No other module touched; existing channels unaffected (§2) |
| Lifecycle-event queue | `unihub.notification.queue` (`../platform/messaging.md` §2) | Async dispatch off the triggering request's critical path (`architecture.md` §4.3) |
| Redelivery dedup (rows) | `UNIQUE (source_event_id, user_id, channel)` + `INSERT … ON CONFLICT DO NOTHING`; `source_event_id` = AMQP `message-id` = `event_publications.id` | INV-11, closed by the database (§3; Phase 4 checklist D31) |
| Redelivery dedup (sends) | Per-row claim on `claimed_at` (conditional `UPDATE`) before calling the provider; released on `UNAVAILABLE`; lease configurable, longer than connect + send timeout and well under the retry chain; any row left `PENDING` → the message is retried, never acked | No two concurrent deliveries send the same row (§3; D33) |
| Delivery results | `SENT` / `REJECTED` (→ `FAILED`, per-recipient permanent) / `DEFERRED` (per-recipient transient: stays `PENDING`, channel continues) / `UNAVAILABLE` (channel-wide: stays `PENDING`, channel skipped for this delivery); any `PENDING` → retry chain, then parking-lot | Prompt delivery through a short provider outage (§2, §5; D32) |
| Provider-side guarantee | At-least-once: crash between accept and mark → one more send | Accepted trade-off (§3; D33) |
| Message type on the shared queue | AMQP `type` = routing key, set by the relay; fallback `x-original-routing-key` → received key; else parking-lot | Three payload types on one queue, routing key lost after a retry hop (§3; D34) |
| Cross-module data | Ports `RecipientDirectory`, `WorkshopDetails`, `WorkshopRecipients` → adapters → `user.api`, `workshop.api`, `registration.api`; read at the Master, at consume time | Module boundaries (`CLAUDE.md`), interaction type 1 (§3; D35) |
| Recipients of cancel/reschedule | Registrations `CONFIRMED` + `RESERVED`, read at consume time; stale reschedule skipped | `docs/requirement.md` §1.3.2/§1.3.3 (§3; D35) |
| Email provider | SMTP via `JavaMailSender`; `unihub.notification.smtp.host` required; timeouts 5s connect / 10s read / 10s write per socket operation; send lease 120s; Mailpit as the test double | Vendor-neutral, bounded (§2; D36) |
| Fan-out | One consumer, two phases; a channel's first `UNAVAILABLE` skips its other rows for that delivery; all types → `EMAIL` + `IN_APP`; listener `concurrency` 2, `prefetch` 1 | Bounded delivery time, no head-of-line blocking (§3; D39) |
| Template scope | One template per `(type, channel)` pair, code-resident; Vietnamese; text + escaped minimal HTML; times in `Asia/Ho_Chi_Minh`; QR e-ticket added in Phase 5 as an inline PNG (ZXing, `Content-ID`) in the confirmation email, a link in-app (Phase 5 checklist D55) | Notification content is a code-level decision, not runtime-configurable data (§4; D37) |
| In-app inbox | `title`/`body` stored on `IN_APP` rows; authenticated, always filtered by the caller's own `sub`; no read/unread flag | `docs/requirement.md` §1.3.2 "in-app notification" (§1; D38) |
| `notifications.channel` values | `EMAIL`, `IN_APP` (extensible via tolerant migration) | `../platform/database-schema.md` §7.1 |

---

## API Surface

The concrete HTTP contract for this module's one endpoint — the in-app notification inbox read, `listMyNotifications` — is specified in `../../../packages/api-contract/paths/notification.yaml` (schema in `../../../packages/api-contract/schemas/notification.yaml`), not repeated here. This file confirms the endpoint's *existence* (the scope note above excludes "the JWT that authenticates" it) without ever naming its path; the path `GET /api/v1/notifications` is the contract's.

**Decided 2026-10-28 (Phase 4 checklist D38), reflected in the contract on 2026-10-29 (task 1.4):** the first gap the contract used to flag (no rendered content to return) is closed by `title`/`body` on `IN_APP` rows (§1, `../platform/database-schema.md` §7.1); the second stays by design — there is no read/unread flag, because no requirement asks for one, and it can be added later by an additive migration. The endpoint needs only an authenticated caller (no permission, like `listMyRegistrations`), always filters by the JWT's `sub`, returns only `IN_APP` rows, newest first, paginated. The "Inferred route" and "gap" flags are gone from the contract files.
