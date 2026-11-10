# Web (Frontend) Coding Standards — UniHub Guidelines

*Status: Draft. Applies to `apps/web` from Phase 1 onward. It is reviewed and extended as real
code lands, the same way `backend-coding-standards.md` grew; anything this file depends on but
cannot decide is listed in §13 (the same Open Items mechanism as `backend-coding-standards.md`
§27).*

This is the frontend counterpart to `backend-coding-standards.md`. It governs everything under
`apps/web`, the **Web Application** container (`architecture.md` §1.2, §2.2): a client-rendered
SPA (React + TypeScript on Vite, TanStack Query + Zustand, Tailwind CSS + Shadcn UI, React Hook
Form + Zod — `proposal.md` §10). It fixes the source layout (a feature-based architecture with four
layers and one-way imports), how the client talks to the Backend API, and how the client upholds
the guarantees the backend designed for idempotency, permission-based access and stale reads.

**Precedence.** This file never overrides `requirement.md`, `architecture.md`, `proposal.md`, the
`docs/technical-design/` files, or `packages/api-contract/`. It *cites* names those sources
already fixed (paths, headers, schema names, enum values, permission codes) and never invents
alternatives. If this file and one of them disagree, they are right and this file has a bug. A
rule here that needs a change in one of them is listed in §13 and made *there* first.

**Out of scope:** the Check-in Client (`apps/mobile`, Check-in Staff only — `proposal.md` §5.1,
§9); test strategy and coverage policy (`docs/testing/`); visual design and copywriting; physical
values such as TTLs, poll intervals and retry budgets, which come from `docs/technical-design/` or
are listed in §13 until they do.

---

## Rules at a Glance

Read this first, then only the sections for the code you are touching. **MUST** and **MUST NOT**
are absolute; a task that seems to need an exception means the design is missing something — stop
and ask.

**Structure**
1. Imports point one way only: `app → routes → features → shared` (§2).
2. MUST NOT import from `features/A` inside `features/B`. Features meet only in `routes/` (or
   `app/`) (§2).
3. From outside a feature, import only `@/features/<name>` (its `index.ts`). No deep imports (§2).
4. `shared/` holds no business knowledge: no endpoint paths, permission codes, statuses or domain
   copy (§2, §4).
5. A feature folder exists only once a screen consumes its endpoints; sub-folders exist only
   with content (§3, §4).

**Data**
6. Every Backend API call is a hand-written function in `features/<name>/api/`, using
   `@/shared/api/http-client` and types from `@/shared/api/schema.gen`. Routes and components
   never call `fetch` or the http client directly (§5, §6, §7).
7. Every feature with queries has a key factory in `api/keys.ts` from its first query. No inline
   literal keys (§7).
8. MUST NOT update capacity/seat counts, registration status or payment status optimistically.
   Wait for the server, write the response into the cache, then invalidate (§7).
9. Server data lives only in the TanStack Query cache, never copied into a Zustand store (§8).
10. Form schemas are hand-written in `features/<name>/model/` and mapped explicitly to contract
    request types (§9).

**Correctness at the edge**
11. `createRegistration` and `chargePayment` MUST send `Idempotency-Key`: one
    `crypto.randomUUID()` per user intent, reused for every retry of that intent (§10).
12. Every non-2xx response becomes an `AppError` built from the contract's Problem Details body;
    UX by status follows the table in §6.
13. Gate UI by permission, never by role name. Guards are UX only; the Backend API is the
    enforcement point (§11).
14. Permission codes, header names, paths and enum values are copied from `packages/api-contract`
    — never invented (§5, §11, §14).

---

## Table of Contents

- [Rules at a Glance](#rules-at-a-glance)
- [1. Purpose & Scope](#1-purpose-scope)
- [2. Architecture & Layering](#2-architecture-layering)
- [3. Feature Map](#3-feature-map)
- [4. Directory Structure](#4-directory-structure)
- [5. API Types](#5-api-types)
- [6. HTTP Client & Error Handling](#6-http-client-error-handling)
- [7. Server State — TanStack Query](#7-server-state-tanstack-query)
- [8. Client State — Zustand & State Taxonomy](#8-client-state-zustand-state-taxonomy)
- [9. Forms & Zod](#9-forms-zod)
- [10. Idempotency for Registration & Payment](#10-idempotency-for-registration-payment)
- [11. Auth & Permission-gated UI](#11-auth-permission-gated-ui)
- [12. Testing](#12-testing)
- [13. Open Items](#13-open-items)
- [14. Glossary & Naming](#14-glossary-naming)
- [15. Design System](#15-design-system)

---

## 1. Purpose & Scope

**Decision.** This file is the mandatory standard for production code under `apps/web/src`. It
covers one client: the Web Application, which serves the Student's whole journey (discovery,
registration, payment, viewing/downloading the QR e-ticket) and the Organizer/Admin catalog and
reporting workflows, behind permission-gated views. It does not cover scanning: the Web
Application has no check-in scanning flow.

**Why.** `architecture.md` §1.2/§2.2 and `proposal.md` §5.1/§9 draw the client split by persona
and connectivity, not by convenience: the Student never touches the Check-in Client, and Check-in
Staff never need the Web Application to scan. A standard that tried to cover both would blur the
offline-first rules the mobile client lives by (`architecture.md` §5.3) into a client that is
always online.

**Example.**

| Concern | Governed by |
|---|---|
| Where a file goes, what it may import | this file, §2–§4 |
| What an endpoint accepts and returns | `packages/api-contract/` (types generated per §5) |
| Why the backend behaves as it does (409 vs 503, replay, staleness) | `docs/technical-design/modules/*.md` |
| What to test and at which level | `docs/testing/` (tools fixed here in §12) |

---

## 2. Architecture & Layering

**Decision.** Source code lives in four layers. Dependencies point one way:

```
app  →  routes  →  features  →  shared
```

| Layer | Holds | Must not |
|---|---|---|
| `app/` | Composition root: providers, router setup, route guards, global error boundary, wiring of `shared` hooks to feature implementations | Contain screens or business rules |
| `routes/` | One file (or folder) per route. Composes features into a page, reads route params, passes permission-derived booleans into feature components, coordinates cross-feature effects (e.g. invalidation, §7) | Hold business logic; call the http client or `fetch` |
| `features/<name>/` | One frontend bounded context (§3): its API functions and hooks, components, form model, client state | Import another feature, `routes/` or `app/` |
| `shared/` | Code with no business knowledge: UI primitives, http client, generic hooks/utils, env config | Import `features/`, `routes/` or `app/` |

The import rules, each one enforced (see Enforcement below):

1. `shared` ✗→ `features`, `routes`, `app`.
2. `features` ✗→ `routes`, `app`.
3. `routes` ✗→ `app`.
4. Feature A ✗→ feature B. When two features must coordinate, a route composes them (passing data
   or callbacks between them). When logic is genuinely shared and has no business knowledge, it
   moves down to `shared/`. Logic that is shared *and* business-specific stays duplicated or is
   re-homed into the one feature that owns it — it never goes into `shared/`.
5. From outside a feature, the only import path is `@/features/<name>` — the feature's `index.ts`,
   its public API. No deep imports (`@/features/workshops/api/keys` is a violation). Inside a
   feature, files use relative imports and never import their own `index.ts` (that creates
   cycles).
6. Only `features/*/api/` imports `@/shared/api/http-client`.
7. `src/testing/` (test-only helpers, §12) may import any layer; no production file imports
   `src/testing/`.

**Why.** The backend draws its module boundaries so that a module never reaches into another's
internals (`architecture.md` §1.1, §6.1; `backend-coding-standards.md` §1). The frontend has the
same failure mode: once `registrations` imports a `workshops` internal, a change inside one
silently breaks the other, and the two can no longer be understood or tested in isolation. The
one-way rule plus a single public entry point per feature is the frontend equivalent of
`com.unihub.<module>.api..` being the only importable package. The type-first layout this
replaces (`components/<domain>/`, `pages/…`, `hooks/<domain>/`) scattered one bounded context
across five top-level folders and had no rule at all for cross-domain imports.

**Enforcement.** The rules above are enforced by lint — never left to review alone. ESLint runs
alongside `oxlint` for the boundary rules only (`apps/web/eslint.config.js`, with
`eslint-plugin-boundaries` 7.x); `oxlint` keeps every other rule, and the `lint` script runs both
(`oxlint && eslint`). Rule 5 is expressed in the same `boundaries/dependencies` rule (a target in
another feature must have `fileInternalPath: 'index.ts'`), because 7.x deprecates the separate
`boundaries/entry-point` rule.

**Example.** Excerpt of `apps/web/eslint.config.js` — the file itself is authoritative:

```js
const featureIndex = { element: { type: 'feature', fileInternalPath: 'index.ts' } }

// settings: 'boundaries/elements' = app, routes, feature (captures featureName), shared, testing;
//           'boundaries/files'    = category 'test' for src/**/*.test.{ts,tsx}
'boundaries/dependencies': ['error', {
  default: 'disallow',
  policies: [
    { from: { element: { type: 'app' } }, allow: { to: [{ element: { types: { anyOf: ['routes', 'shared'] } } }, featureIndex] } },
    { from: { element: { type: 'routes' } }, allow: { to: [{ element: { type: 'shared' } }, featureIndex] } },
    { from: { element: { type: 'feature' } }, allow: { to: { element: { type: 'shared' } } } },
    { from: { element: { type: 'feature' } },
      allow: { to: { element: { type: 'feature', captured: { featureName: '{{from.element.captured.featureName}}' } } } } },
    { from: { element: { type: 'shared' } }, allow: { to: { element: { type: 'shared' } } } },
    { from: { element: { type: 'testing' } }, allow: { to: [{ element: { types: { anyOf: ['app', 'routes', 'shared', 'testing'] } } }, featureIndex] } },
    { from: { file: { categories: 'test' } }, allow: { to: { element: { type: 'testing' } } } },
    { from: { file: { path: '!src/features/*/api/**' } },
      disallow: { to: { file: { path: 'src/shared/api/http-client.ts' } } } },
  ],
}]
```

---

## 3. Feature Map

**Decision.** Features follow the six backend modules (`architecture.md` §1.1, §6.1), but only
where a module has a surface on the Web Application, and each feature is bound to the contract
operations (`operationId`s in `packages/api-contract/paths/*.yaml`) it consumes. A feature folder
is created when the first screen consuming those operations is built — never ahead of it.

| Feature | Backend module | Contract tag | Operations used by the Web Application | Status |
|---|---|---|---|---|
| `workshops` | Workshop | `Workshop` | `listWorkshops`, `getWorkshop`; Organizer: `createWorkshop`, `updateWorkshop`, `publishWorkshop`, `cancelWorkshop`, `requestBrochureUploadUrl`, `confirmBrochureUpload` | **active** — catalog + detail (`routes/public/`); Organizer CRUD planned |
| `auth` | User | `User` | `login` | **active** — sign-in (`routes/auth/`), session store, guards (`app/router/guards/`) |
| `registrations` | Registration | `Registration` | `createRegistration`, `listMyRegistrations`, `getRegistration` (renders/downloads the QR e-ticket), `cancelRegistration`; reporting: `listWorkshopRegistrations` | **active** — register button, my-registrations list, hold countdown; the e-ticket page (`RegistrationTicketView`, `TicketQrCode`; `/me/registrations/:id`); `cancelRegistration` and `listWorkshopRegistrations` planned |
| `payments` | Payment | `Payment` | `chargePayment`, `getPayment` | **active** — checkout (`routes/me/`) with the test-token dropdown |
| `checkin` | Check-in | `Check-in` | `listWorkshopAttendances` (Organizer check-in reporting only) | planned |
| `notifications` | Notification | `Notification` | `listMyNotifications` | **active** — the inbox (`routes/me/NotificationsPage.tsx`, `/me/notifications`) and the header link while signed in |
| `legacy-sync` | User | `User` | `listSyncRuns`, `getSyncRun`, `listQuarantinedRecords`, `resolveQuarantinedRecord` | planned |

Deliberately **not** created:

| Would-be feature | Why not |
|---|---|
| Check-in scanning (`getVerificationKey`, `syncScanBatch`) | Check-in Staff use the mobile client only (`proposal.md` §5.1, §9; `architecture.md` §2.2). `checkin` above is reporting only. |
| Role / permission administration | No API surface exists: roles and permissions are migration-seeded data (`packages/api-contract/README.md`, Key decisions — "RBAC role/permission management"). |
| Payment webhook (`handlePaymentGatewayWebhook`) | Called by the Payment Gateway, never by a UniHub client (`technical-design/modules/payment.md` §7). |

**Why.** A feature named after the module and contract tag it consumes keeps one vocabulary from
the database to the screen, the same "cited, never invented" rule as
`backend-coding-standards.md` §14.2. Two features deviate from the module name, each for a stated
reason: `auth` is the User module's session surface, named for what the client does with it; and
`legacy-sync` is a second User-module feature because Organizer/Admin review of quarantined
student-sync records (`requirement.md` §1.5, §2.10) has nothing in common with signing in, and
its schema names (`LegacySyncRun`, `LegacySyncError`) and paths (`/api/v1/legacy-sync/…`) already
carry the name. Check-in reporting is named `checkin` because the operation belongs to the
Check-in tag and module (`paths/checkin.yaml#/workshopAttendances`), even though the Web never
scans. Creating feature folders ahead of their screens produces empty scaffolding that drifts
from the contract before it is ever used.

**Example.** The notification inbox is in the contract and the `notifications` feature is built, but
the contract also states what it cannot provide (`schemas/notification.yaml#/Notification`): no
read/unread state. The feature therefore shows each item's stored `title` and `body` exactly as the
Backend rendered them (Phase 4 checklist D38), never rebuilds the wording from `NotificationType`,
and offers no "mark as read", rather than inventing it.

---

## 4. Directory Structure

**Decision.** `apps/web/src` has this shape. A feature's sub-folders exist only when they have
content; no empty folders, no placeholder files.

```
src/
  app/
    providers/            # QueryClientProvider, http-client wiring, global error boundary
    router/
      routes.tsx          # the route table (RouteObject[])
      guards/             # RequireSession, RequirePermission — UX gating only (§11)
    App.tsx
    main.tsx
  routes/
    public/               # no login: catalog, workshop detail
    auth/                 # sign-in
    me/                   # the signed-in user's own data: registrations, e-tickets, checkout, inbox
    admin/                # Workshop Administration (URL /admin, D112): the managed list, create, edit, report; legacy-sync review is deferred (Phase 8 D105)
  features/
    <name>/
      api/                # requests.ts, queries.ts, mutations.ts, keys.ts (§7)
      components/
        __tests__/        # tests of the files in components/ — every folder with tests has one (§12)
      hooks/              # UI logic specific to this feature (only when needed)
      model/              # Zod form schemas, view-model types, DTO ↔ form/view mappers
      store/              # Zustand slice — ONLY when there is genuine client state (§8)
      index.ts            # public API
  shared/
    api/
      http-client.ts
      errors.ts
      schema.gen.ts       # openapi-typescript output — gitignored, never committed (§5)
    ui/                   # Shadcn primitives (generator-owned)
    components/           # generic composites (DataTable, EmptyState, …) with no business logic
    hooks/
    lib/                  # utils.ts (cn), query-client.ts, format.ts, …
    config/               # env.ts — import.meta.env parsed with Zod, fail-fast
    store/                # global UI-only state, when it exists (§8)
  assets/
  styles/
  testing/                # MSW server + handlers, render utilities, factories (§12)
```

**Route areas.** Areas group URLs by *who may reach them*, never by role name:

| Area | URL prefix | Access check | Basis |
|---|---|---|---|
| `public/` | none (`/workshops`, `/workshops/:workshopId`) | none | `requirement.md` §1.3.1; `listWorkshops`/`getWorkshop` declare `security: []` |
| `auth/` | `/login` | none | `auth-and-identity.md` §4 (re-authenticate via the login endpoint) |
| `me/` | `/me` | a valid session; no specific permission | `createRegistration`, `listMyRegistrations`, `getRegistration`, `chargePayment`, `getPayment`, `listMyNotifications` declare no `x-required-permission` — they are ownership-scoped, not capability-scoped |
| `admin/` | `/admin` | the permission each page needs: the frame needs any of `WORKSHOP_CREATE`, `WORKSHOP_EDIT`, `REPORT_VIEW`; each page its own (`WORKSHOP_EDIT` for the list and edit, `WORKSHOP_CREATE` for create, `REPORT_VIEW` for the report). `STUDENT_SYNC_VIEW`/`STUDENT_SYNC_RESOLVE` pages are not built (Phase 8 D105) | `requirement.md` §1.3.3 "Workshop Administration", §1.3.4 (brochure upload); the feature that holds the components is `features/administration/` (and `features/reports/` for the report) |

**Naming.**

- Components: PascalCase file named after its single named export (`WorkshopCatalogPage.tsx`
  exports `WorkshopCatalogPage`). Route components keep the `…Page` suffix.
- Everything else: kebab-case (`http-client.ts`, `session-store.ts`, `workshop-form.schema.ts`).
  `shared/ui/` follows whatever the Shadcn generator emits (`button.tsx`).
- Tests: `__tests__/Foo.test.tsx` in the folder that holds `Foo.tsx` (§12) — never next to it.
- Named exports only; default exports only where a tool requires them (config files).
- No TypeScript `enum`: `tsconfig.app.json` sets `erasableSyntaxOnly`. Use `as const` objects or
  the generated string-literal unions.
- No separate `utils/` next to `shared/lib/`.

**Why.** Grouping by feature keeps one bounded context in one folder. The access-area split
replaces the old `pages/dashboard/{admin,organizer,student}` tree because role-named folders encode
exactly what the system is designed *not* to depend on: roles are data, reconfigurable without a
code change (`requirement.md` §1.2; `architecture.md` §5.1), and a page belongs to whoever holds
its permission. Under role folders, a new role such as "Department Lead" (`requirement.md` §1.2)
holding `REPORT_VIEW` but not `WORKSHOP_EDIT` has no folder that describes it; "admin" and
"organizer" split what the SRS treats as one user group ("Organizers/Admin"); and "student" is not
a permission at all — the contract grants Student endpoints to any authenticated caller and checks
ownership (`paths/registration.yaml#/registrations`). The area is the SRS's own
"Workshop Administration" (`requirement.md` §1.3.3): the feature folder keeps the full name
(`features/administration/`), while the route folder and the URL are the shorter `admin` that Phase 8 chose
(D112), since a URL is read by people. There is no `utils/` next to `lib/` for the same reason as before: two general-helper
directories split one kind of code with no rule for which one a helper belongs in. The Shadcn
aliases in `apps/web/components.json` still point at the old paths (`ui` → `@/components/ui`,
`utils` → `@/lib/utils`) and must move to `@/shared/ui` and `@/shared/lib/utils` with the code
(§13 item 2).

**Example.** A feature's public API — the only file outside code may import:

```ts
// features/workshops/index.ts
export { workshopKeys } from './api/keys'
export { useWorkshop, useWorkshops } from './api/queries'
export { WorkshopSummaryList } from './components/WorkshopSummaryList'
export { WorkshopDetailView } from './components/WorkshopDetailView'
export type { WorkshopFilters } from './api/requests'
```

---

## 5. API Types

**Decision.** TypeScript types are generated with `openapi-typescript` from
`packages/api-contract/openapi.yaml` — types only, **no generated client** and no generated
request functions. The output is `src/shared/api/schema.gen.ts`; it is gitignored and regenerated
automatically by `predev`/`prebuild`. API functions are hand-written in `features/<name>/api/`
(§6, §7), typed with `components['schemas'][…]`, `operations[…]` and `paths[…]` from the
generated file.

**Why.** `packages/api-contract` is the declared source of truth for the HTTP shape (`CLAUDE.md`;
`packages/api-contract/README.md`). Generating types from it keeps the frontend from drifting out
of sync with the contract, playing the same role for the frontend that `bundleApiContract` plays
for the backend, without pulling in a generated client whose conventions (error handling,
idempotency headers, auth wiring) would then fight §6 and §10. The generated file sits in
`shared/api/` because every feature needs it and it carries no logic. Two limits to know:
`openapi-typescript` does not emit the `x-required-permission` extension, so permission codes are
copied by hand (§11); and the contract's error body is emitted as `components['schemas']['error']`
— the generated name of `common/error.yaml`, which the root `openapi.yaml` references by file
rather than by a named schema.

**Example.**

```json
// apps/web/package.json
{
  "scripts": {
    "predev": "openapi-typescript ../../packages/api-contract/openapi.yaml -o src/shared/api/schema.gen.ts",
    "prebuild": "openapi-typescript ../../packages/api-contract/openapi.yaml -o src/shared/api/schema.gen.ts",
    "dev": "vite",
    "build": "tsc -b && vite build"
  }
}
```

```gitignore
# apps/web/.gitignore — generated by openapi-typescript (web-coding-standards.md §5)
src/shared/api/schema.gen.ts
```

```ts
// features/registrations/api/requests.ts — types come only from the generated file
import type { components, operations } from '@/shared/api/schema.gen'

export type Registration = components['schemas']['Registration']
export type CreateRegistrationRequest = components['schemas']['CreateRegistrationRequest']
type CreateRegistrationHeaders = operations['createRegistration']['parameters']['header']
```

---

## 6. HTTP Client & Error Handling

**Decision.**

- `shared/api/http-client.ts` is the single way to call the Backend API. It prefixes the base URL
  from `shared/config/env.ts`, sends `Authorization: Bearer <token>` whenever a session exists
  (the `bearerAuth` scheme, `packages/api-contract/common/security-schemes.yaml#/bearerAuth`), and
  turns every non-2xx response or network failure into an `AppError`.
- The client gets the token and reports `401`s through two callbacks that `app/` registers at
  startup (`configureHttpClient`). `shared/` never imports the session store from `features/auth`.
- The `401` callback fires only when the failed request carried a token. A `401` from `login`
  (no token sent) means bad credentials, not an expired session (`paths/user.yaml#/login`).
- The token is sent on public operations too: `getWorkshop` returns a `DRAFT` workshop only to a
  caller holding `WORKSHOP_CREATE`/`WORKSHOP_EDIT` (`paths/workshop.yaml#/workshopById`).
- The callbacks live in `shared/api/http-client-config.ts` (`configureHttpClient`, re-exported by `http-client.ts`). `app/` imports them from there, because the lint rule of §2 (rule 6) lets only `features/<name>/api/` import `http-client.ts` itself.
- The client does not generate `X-Correlation-Id`; the server always returns one, and errors carry
  it in the body (`common/headers.yaml#/CorrelationIdResponse`, `common/error.yaml`).
- The client is for the Backend API only. The brochure upload `PUT`s directly to the presigned
  S3/MinIO URL with plain `fetch` and **no** `Authorization` header, from
  `features/workshops/api/` (`technical-design/modules/workshop.md` §7). Before requesting the URL,
  the client checks `file.size` ≤ 10MB and offers an `accept=".pdf"` picker (`workshop.md` §6).
- `shared/api/errors.ts` normalizes the contract's error body — RFC 7807 Problem Details,
  `common/error.yaml` (`type`, `title`, `status`, `detail`, `instance`, `correlationId`) — into
  one `AppError` type. No field outside that schema is read or assumed.

| Status | Meaning in this API | Client behavior | Basis |
|---|---|---|---|
| network failure | no response | `AppError` with `status: null`; transient (retry allowed per §7/§10) | — |
| `400` | malformed request | form-level error; Zod should have caught it first. The body has no per-field errors, so none are mapped | `common/error.yaml`; `backend-coding-standards.md` §17 |
| `401` | missing, invalid or expired token | session ended → clear session and query cache → sign-in, keeping the return path. No refresh: there is no refresh token | `architecture.md` §5.2; `auth-and-identity.md` §4 |
| `403` | valid token without the permission, or not the owner | no-permission state; never retried | `architecture.md` §5.1; `paths/registration.yaml#/registrationById` |
| `404` | not found — or a `DRAFT` hidden from this caller | not-found state; the two are deliberately indistinguishable | `paths/workshop.yaml#/workshopById` |
| `409` | conflict with current state — the reason is in the problem `type` (`backend-coding-standards.md` §17): `sold-out`, `already-registered`, `registration-not-open`, `registration-not-reserved`, `payment-in-progress`, or `idempotency-key-in-use` | a specific message per `type`; never retried with the same key — except `idempotency-key-in-use`, which is retried after a short delay with the same key (§10) | `proposal.md` §11.1, §12; `paths/registration.yaml#/registrations` |
| `429` | rate limit (per-user on `createRegistration`, per-IP on `login`, `listWorkshops`, `getWorkshop`) | no automatic retry; tell the user, and use `Retry-After` (seconds) for the wait | `architecture.md` §4.2; `technical-design/platform/distributed-coordination.md` §3; `common/headers.yaml#/RetryAfter` |
| `422` | `urn:unihub:problem:idempotency-key-reused` — the same key was sent with a different body | a client bug: log it, start a new intent; never retried | `backend-coding-standards.md` §21 |
| `503` on `chargePayment` | the payment gateway was not reached (circuit open, connection failure); seat hold untouched — a timeout **after** the confirm was sent is a `201` `PENDING`, not a `503` | an immediate message **inside the payment flow only** (the seat stays held for the rest of its 15 minutes); no app-wide outage UI — browsing keeps working | `proposal.md` §11.3; `requirement.md` §2.7; `technical-design/modules/payment.md` §3 |
| `503` on `createRegistration`/`cancelRegistration` | seat-lock contention or Redis down — transient, the seat may still be free | "try again shortly"; retry with the same key, honoring `Retry-After` (§10) | `technical-design/modules/registration.md` §2 |

Branch on the problem `type`, never on `detail` or `title`. The `type` values are a stable contract
(`packages/api-contract/common/error.yaml`); an error the client does not need to branch on keeps
`about:blank`. **An unrecognised `type` must fall back to the status-code behavior** in the table
above — never throw, never show a blank state — so the server can add a `type` without breaking
deployed clients. User-facing copy is owned by the client, keyed by operation and status; `detail` is not shown as
the primary message, because the contract fixes neither its wording nor its language.
`correlationId` is shown on error states as the reference a user hands to support
(`common/error.yaml`).

**Why.** One client means one place where auth, base URL and error shape are decided, so a
feature cannot quietly send an unauthenticated request or parse an error its own way. Injecting
the token getter keeps `shared/` free of business knowledge (§2) while still letting it attach the
session. Every status in the table is a distinct backend outcome the design spent effort keeping
distinct — `401` vs. `403` (`auth-and-identity.md` §5), `409` vs. `503` (`registration.md` §2) —
and collapsing them in the client would throw that work away. Scoping the payment `503` to the
payment flow is the client half of the fault-isolation guarantee: a gateway outage must not
cascade into unrelated functionality (`proposal.md` §11.3, §12 "Fault Isolation").

**Example.**

```ts
// shared/api/errors.ts
import type { components } from '@/shared/api/schema.gen'

export type ProblemDetail = components['schemas']['error'] // common/error.yaml (RFC 7807)

// Explicit fields, not constructor parameter properties: erasableSyntaxOnly (§4) rejects those.
export class AppError extends Error {
  readonly status: number | null // null: no HTTP response (network failure, abort)
  readonly problem: ProblemDetail | null // null: body was not application/problem+json
  readonly retryAfterSeconds: number | null

  constructor(status: number | null, problem: ProblemDetail | null, retryAfterSeconds: number | null, options?: ErrorOptions) {
    super(problem?.title ?? (status === null ? 'Network error' : `HTTP ${status}`), options)
    this.status = status
    this.problem = problem
    this.retryAfterSeconds = retryAfterSeconds
  }
}

/** Transient: the same request may succeed later (network failure, 429, 503). */
export function isTransient(error: AppError): boolean {
  return error.status === null || error.status === 429 || error.status === 503
}

export async function toAppError(response: Response): Promise<AppError> {
  const isProblem = response.headers.get('Content-Type')?.includes('application/problem+json')
  const problem = isProblem ? ((await response.json()) as ProblemDetail) : null
  const retryAfter = Number(response.headers.get('Retry-After'))
  return new AppError(response.status, problem, Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter : null)
}
```

```ts
// shared/api/http-client.ts
import { env } from '@/shared/config/env'
import { AppError, toAppError } from './errors'

type HttpClientHooks = {
  getAccessToken: () => string | null
  onUnauthorized: () => void
}

let hooks: HttpClientHooks = { getAccessToken: () => null, onUnauthorized: () => {} }

/** Called once by app/ at startup; shared/ never imports the session from features/auth. */
export function configureHttpClient(next: HttpClientHooks): void {
  hooks = next
}

export async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json, application/problem+json')
  const token = hooks.getAccessToken()
  if (token) headers.set('Authorization', `Bearer ${token}`)

  let response: Response
  try {
    response = await fetch(`${env.apiBaseUrl}${path}`, { ...init, headers })
  } catch (cause) {
    throw new AppError(null, null, null, { cause })
  }
  if (!response.ok) {
    const error = await toAppError(response)
    if (response.status === 401 && token) hooks.onUnauthorized() // login's own 401 never ends a session
    throw error
  }
  return (response.status === 204 ? undefined : await response.json()) as T
}
```

```ts
// shared/config/env.ts — parsed once at import; a bad value fails at startup, not mid-request
import { z } from 'zod'

const schema = z.object({
  // Empty = same origin. In dev the Vite proxy forwards /api to the Backend API (vite.config.ts);
  // contract paths already carry the /api/v1 prefix.
  VITE_API_BASE_URL: z.string().default(''),
})

const parsed = schema.parse(import.meta.env)
export const env = { apiBaseUrl: parsed.VITE_API_BASE_URL }
```

---

## 7. Server State — TanStack Query

**Decision.**

- **Every Backend API call goes through `features/<name>/api/`.** `requests.ts` holds the plain
  request functions (one per `operationId`), `queries.ts`/`mutations.ts` the hooks, `keys.ts` the
  key factory. Routes and components call hooks, never the http client.
- **Every feature with queries has a key factory in `api/keys.ts` from its first query**, shaped
  `{ all, lists, list(filters), details, detail(id) }`. No inline literal keys anywhere.
- **Hook names:** `use<Noun>` for queries (`useWorkshop`, `useMyRegistrations`),
  `use<Verb><Noun>` for mutations (`useRegisterForWorkshop`, `useCancelWorkshop`).
- **Cross-feature invalidation happens in the route layer.** Each feature exports its key factory
  through `index.ts`; a mutation hook invalidates its *own* feature's keys and accepts an
  `onSuccess` callback; the route that composes two features passes a callback that invalidates
  the other feature's keys. A feature never imports another feature's keys — only routes (and
  `app/`) see more than one feature.
- **No optimistic updates for capacity/seat counts, registration status or payment status.** Wait
  for the server, write the response, then invalidate. An optimistic update is allowed only when
  all three hold: the data is not a shared resource others compete for (no capacity, money or
  lifecycle status); a rollback has no effect outside this browser; and the server cannot reject
  the change for a business reason. Today the only operation that qualifies is toggling a
  quarantined record's `reviewed` flag (`resolveQuarantinedRecord` — a plain triage flag,
  `technical-design/modules/user.md` §6).
- **Read-after-write:** after a successful mutation, write the response body into the detail
  cache with `setQueryData` (create/update responses return the same schema as the detail query),
  then invalidate the affected lists.
- **Seat availability is advisory.** `Workshop.seatsTaken` may be stale; a displayed count is never
  a promise, and a `409` after a non-zero count is a normal outcome, not an error state. The
  detail query uses `staleTime` 3s and `refetchInterval` 5s for seats, **only** while the tab is
  visible and the detail page is mounted (`refetchIntervalInBackground: false`) — checklist D15.
  The load this adds at peak (~2,400 rps at 12,000 concurrent users) is a Phase 9 review point.
- **Status polling.** `getPayment` and `getRegistration` poll every 2s while a payment or a paid
  registration is not final — including the "payment `SUCCEEDED`, registration still `RESERVED`"
  window (`technical-design/modules/registration.md` §5). Polling stops at a final state, or after
  2 minutes, when the UI shows "still processing" with a manual refresh. A registration that has
  ended (`EXPIRED`, `CANCELLED`) does not end the payment's story: a payment still `PENDING`, a
  late `SUCCEEDED`, and a `REFUND_REQUIRED` are followed on until `FAILED`, `CANCELLED` or
  `REFUNDED`, because the refund is made in the background after the registration is final
  (Phase 3 checklist 6.1).
- **Retry policy.** Queries never retry a `4xx`; other failures use TanStack's default retry.
  Mutations never retry automatically, except those guarded by `Idempotency-Key` (§10), which may
  be retried — manually by the user after any transient failure, or automatically only after a
  network failure or a `503` (honoring `Retry-After`, plus jitter; a `429` is never retried
  automatically, §6) — always reusing the **same** key. Automatic retries are capped at **3**.
- **Session changes clear the cache.** On sign-in and sign-out, `queryClient.clear()` (§11).

**Why.** A key factory from day one replaces the old rule (inline literal keys until a second
consumer appears) because invalidation crosses features: a successful registration must refresh
the workshop detail's seat count, and an organizer's cancel must refresh the catalog. Scattered
literal keys break that silently — a typo'd key invalidates nothing and no test fails. Doing
cross-feature invalidation in the route keeps rule 4 of §2 intact: the route is already the place
where both features are visible. Optimistic updates are banned exactly where the backend designed
the client *not* to know the answer in advance: the seat decision is made transactionally at the
Master under the lock, never from a prior read (`architecture.md` §4.1, §4.2;
`technical-design/modules/registration.md` §2), and a payment charge always returns `PENDING`
first (`paths/payment.yaml#/payments`). Showing "registered" or "paid" before the server says so is
showing a guess that can be wrong in the exact peak-load case the system is built for. The
read-after-write rule follows from replica lag: lists are routine reads served from a Replica that
can lag the Master, while the mutation response *is* the post-write state (`architecture.md`
§4.1); and the workshop catalog list is additionally cached for up to 30s with no write-path
invalidation (`technical-design/modules/workshop.md` §2), so an invalidated list can still come
back without the change. Seat counts are near-real-time by design — the `seats` cache entry lives
3s (`workshop.md` §2), so refetching faster than that cannot show a fresher number. Not retrying
`4xx` avoids hammering a request that will fail the same way; not auto-retrying mutations avoids
duplicate writes, which only an idempotency key makes safe.

**Example.**

```ts
// features/workshops/api/keys.ts
import type { WorkshopFilters } from './requests'

export const workshopKeys = {
  all: ['workshops'] as const,
  lists: () => [...workshopKeys.all, 'list'] as const,
  list: (filters: WorkshopFilters) => [...workshopKeys.lists(), filters] as const,
  details: () => [...workshopKeys.all, 'detail'] as const,
  detail: (id: string) => [...workshopKeys.details(), id] as const,
}
```

```ts
// features/workshops/api/requests.ts
import { request } from '@/shared/api/http-client'
import { toQueryString } from '@/shared/lib/query-string'
import type { components, operations } from '@/shared/api/schema.gen'

export type Workshop = components['schemas']['Workshop']
export type WorkshopFilters = NonNullable<operations['listWorkshops']['parameters']['query']>

export const listWorkshops = (filters: WorkshopFilters) =>
  request<components['schemas']['WorkshopListResponse']>(`/api/v1/workshops${toQueryString(filters)}`)

export const getWorkshop = (id: string) =>
  request<Workshop>(`/api/v1/workshops/${encodeURIComponent(id)}`)
```

```ts
// features/workshops/api/queries.ts
import { useQuery } from '@tanstack/react-query'
import { workshopKeys } from './keys'
import { getWorkshop, listWorkshops, type WorkshopFilters } from './requests'

export const useWorkshops = (filters: WorkshopFilters) =>
  useQuery({ queryKey: workshopKeys.list(filters), queryFn: () => listWorkshops(filters) })

export const useWorkshop = (id: string) =>
  useQuery({ queryKey: workshopKeys.detail(id), queryFn: () => getWorkshop(id) })
```

```ts
// shared/lib/query-client.ts
import { QueryClient } from '@tanstack/react-query'
import { AppError } from '@/shared/api/errors'

declare module '@tanstack/react-query' {
  interface Register {
    defaultError: AppError
  }
}

const isClientError = (error: unknown) =>
  error instanceof AppError && error.status !== null && error.status >= 400 && error.status < 500

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: (failureCount, error) => !isClientError(error) && failureCount < 3 }, // 3 = TanStack's default
    mutations: { retry: false },
  },
})
```

```tsx
// routes/public/WorkshopDetailPage.tsx — the route coordinates the two features
import { useQueryClient } from '@tanstack/react-query'
import { useParams } from 'react-router'
import { useSession } from '@/features/auth'
import { WorkshopDetailView, useWorkshop, workshopKeys } from '@/features/workshops'
import { RegisterButton } from '@/features/registrations'

export function WorkshopDetailPage() {
  const { workshopId = '' } = useParams()
  const queryClient = useQueryClient()
  const workshop = useWorkshop(workshopId)
  const session = useSession() // the route reads the session; features only get booleans (§11)

  return (
    <WorkshopDetailView query={workshop}>
      <RegisterButton
        workshopId={workshopId}
        signedIn={session !== null} // signed out: the button links to /login instead

        // seatsTaken changed: refresh the detail. WorkshopSummary carries no seat count,
        // so the catalog lists need no invalidation for a registration.
        onRegistered={() => queryClient.invalidateQueries({ queryKey: workshopKeys.detail(workshopId) })}
      />
    </WorkshopDetailView>
  )
}
```

---

## 8. Client State — Zustand & State Taxonomy

**Decision.** Every piece of state has exactly one home:

| Kind | Home | Examples |
|---|---|---|
| Server state | TanStack Query cache (§7) | workshops, registrations, payment status |
| Client global state | Zustand | the session snapshot (§11); UI preferences |
| Form state | React Hook Form (§9) | the workshop create/edit form |
| URL state | route params and search params | catalog filters (`q`, `speaker`, `location`, `dateFrom`, `dateTo`), `page`/`size` |
| Local state | `useState` | an open dialog, a hover |

- Zustand holds genuine client state only. Never copy server data into a store — not even "to
  avoid prop drilling" (use the query hook where it's needed).
- A store that belongs to a feature lives in `features/<name>/store/` and is exposed only through
  that feature's `index.ts` (as hooks/functions, not the raw store where avoidable).
- Truly global, business-free UI state lives in `shared/store/`, not `app/`.

**Why.** Two copies of server data drift: the store keeps the value from before the last
invalidation while the cache has the new one, and the UI shows both. Filters and pagination
belong in the URL so a filtered catalog is linkable and survives reload, and because the catalog
filters are server query parameters (`paths/workshop.yaml#/workshops`), not UI preferences. Global
UI state goes in `shared/store/` rather than `app/` because features and routes must be able to
read it, and `app/` is unreachable from both by the dependency rule (§2).

**Example.**

```ts
// features/auth/store/session-store.ts
import { create } from 'zustand'
import type { Session } from '../model/session'

type SessionState = {
  session: Session | null
  start: (session: Session) => void
  end: () => void
}

// sessionStorage (auth-and-identity.md §7, checklist D14): survives a reload, not the tab. An expired
// snapshot is discarded at start-up; end() runs on sign-out and on any 401.
const KEY = 'unihub.session'
const restore = (): Session | null => {
  const raw = sessionStorage.getItem(KEY)
  const session = raw ? (JSON.parse(raw) as StoredSession) : null
  return session && session.expiresAt > Date.now() ? fromStored(session) : (sessionStorage.removeItem(KEY), null)
}
export const useSessionStore = create<SessionState>()((set) => ({
  session: restore(),
  start: (session) => { sessionStorage.setItem(KEY, JSON.stringify(toStored(session))); set({ session }) },
  end: () => { sessionStorage.removeItem(KEY); set({ session: null }) },
}))
```

---

## 9. Forms & Zod

**Decision.** Form validation schemas are hand-written with Zod and kept separate from the
contract-generated types (§5). They live in `features/<name>/model/<form>.schema.ts`, next to an
explicit mapper from form values to the contract request type. Constraints that the contract
fixes (required fields, `maxLength`, `minimum`, conditional rules) are copied from it, never
loosened or invented. Edit forms on a `PATCH` operation send only the fields the user changed.

**Why.** Generated types describe the wire shape the backend expects, which is not the shape that
makes good form UX: a `date-time` field is an ISO string on the wire but a wall-clock
`datetime-local` value in the form; a price is a nullable number on the wire but an input that
only matters when "paid" is ticked. Collapsing the two leaks the wire representation into forms
and breaks form code on unrelated contract changes. Copying the contract's constraints into Zod
catches invalid input before it reaches the network (`proposal.md` §10), and the backend still
enforces them (`common/error.yaml` `400`). The contract states two cross-field rules it cannot
express structurally — `endTime` after `startTime`, and `price` present iff `isPaid`
(`schemas/workshop.yaml#/CreateWorkshopRequest`) — so the form must. `UpdateWorkshopRequest` is a
partial update where "only present fields change" and `isPaid` is not editable, which is why edit
forms send dirty fields only. The previous example in this file (a registration form with
`studentId`, `fullName`, `dietaryNote`) did not match the contract: `CreateRegistrationRequest`
has exactly one field, `workshopId` (`schemas/registration.yaml`), so registering is a single
confirm action with no form. It is replaced by the workshop form below, which is real.

**Example.**

```ts
// features/workshops/model/workshop-form.schema.ts
import { z } from 'zod'
import type { components } from '@/shared/api/schema.gen'
import { campusLocalToIso } from '@/shared/lib/datetime'

type CreateWorkshopRequest = components['schemas']['CreateWorkshopRequest']

// Bounds copied from CreateWorkshopRequest (packages/api-contract/schemas/workshop.yaml).
export const workshopFormSchema = z
  .object({
    title: z.string().trim().min(1).max(255),
    description: z.string().trim(),
    speakerName: z.string().trim().min(1).max(255),
    speakerBio: z.string().trim(),
    location: z.string().trim().min(1).max(255),
    startTime: z.string().min(1), // <input type="datetime-local"> value, e.g. "2026-10-20T09:00"
    endTime: z.string().min(1),
    capacity: z.number().int().min(0),
    isPaid: z.boolean(),
    price: z.number().positive().nullable(),
  })
  // Same-format datetime-local strings compare correctly as strings.
  .refine((v) => v.endTime > v.startTime, { path: ['endTime'], message: 'End must be after start.' })
  .refine((v) => (v.isPaid ? v.price !== null : v.price === null), {
    path: ['price'],
    message: 'A paid workshop needs a price; a free one has none.',
  })

export type WorkshopFormValues = z.infer<typeof workshopFormSchema>

// Explicit map — form shape != CreateWorkshopRequest shape.
export function toCreateWorkshopRequest(values: WorkshopFormValues): CreateWorkshopRequest {
  return {
    title: values.title,
    description: values.description || null,
    speakerName: values.speakerName,
    speakerBio: values.speakerBio || null,
    location: values.location,
    startTime: campusLocalToIso(values.startTime),
    endTime: campusLocalToIso(values.endTime),
    capacity: values.capacity,
    isPaid: values.isPaid,
    price: values.isPaid ? values.price : null,
  }
}
```

```ts
// shared/lib/datetime.ts — illustrative: interprets a datetime-local value in the campus zone
// (UTC+7, no DST), the same zone shared/lib/format.ts already pins for display.
export const campusLocalToIso = (local: string) => `${local.length === 16 ? `${local}:00` : local}+07:00`
```

---

## 10. Idempotency for Registration & Payment

**Decision.**

- The two idempotency-guarded operations send the `Idempotency-Key` header
  (`packages/api-contract/common/headers.yaml#/IdempotencyKey`, `format: uuid`, required):
  `createRegistration` (`POST /api/v1/registrations`) and `chargePayment`
  (`POST /api/v1/payments/charge`). No other operation sends it.
- **One key per user intent.** Generate it with `crypto.randomUUID()` on the first submit of an
  intent and reuse it for every retry and double-click of that intent.
- **The intent ends — and the next submit gets a new key — when:**
  - the server returns success (`201`);
  - the server returns a definitive failure: any `4xx` other than `429` (e.g. `409` sold out,
    `403`, `404`, `400`);
  - the user starts a different intent, including changing the request body (e.g. a different
    `paymentMethodToken`). A key is never reused with a different body — the server answers `422`
    if it is (`backend-coding-standards.md` §21 rule 4); treat `422` as a client bug, not a retry case;
  - a payment the key created ends `FAILED` or `CANCELLED` (observed via `getPayment`): trying
    again — even with the same card — is a new attempt with a new key. Retrying the old key only
    replays the old outcome (`technical-design/modules/payment.md` §8).
- **The intent continues — same key — after a transient failure:** no response (network), `429`,
  `503`, or `409` "this key is still being processed" (a previous attempt with the same key has not
  finished; retry after a short delay and it replays that attempt's result).
- Disable the submit control while `isPending`. That is UX only; the key is the guarantee.
- `chargePayment` may take up to ≈ 6.5s (two gateway calls, `technical-design/modules/payment.md` §2): never
  abort it sooner than 10s. If the user leaves anyway, the next attempt reuses the same key and replays
  the outcome — it can never charge twice.

**Why.** `requirement.md` §1.5/§2.8 and `proposal.md` §11.2 require that a student is never
registered or charged twice "even if the student repeatedly clicks the register button due to
network lag", and the backend implements it by recording the result under the key in the same
transaction as the effect (`architecture.md` §4.5). That only works if the client sends the
*same* key for the same intent: a fresh key per click turns every retry into a new request and
defeats the mechanism. The end-of-intent rules mirror what the backend records: a retry carrying a
key that already got `409` gets the same `409` again, even if a seat has since freed up
(`paths/registration.yaml#/registrations`; `technical-design/modules/registration.md` §4), so
trying again *as a new attempt* needs a new key. After a network failure the client cannot know
whether the server committed — reusing the key turns a committed request into a replay of its
result instead of a second registration. A `503` is "transient, safe to retry"
(`paths/registration.yaml`, `paths/payment.yaml`): the backend never records a `503` under the key
(`backend-coding-standards.md` §21 rule 7), so a same-key retry executes for real. Keys are scoped
to the signed-in user on the server, so two users can never collide on a key. If the component holding the key unmounts
mid-intent, the next mount starts a new intent; the database backstops still hold — at most one
active registration per user and workshop (the partial unique index behind
`createRegistration`'s `409`, `database-schema.md` §4.1) and at most one *live* payment per registration (`chargePayment`'s `409`,
`database-schema.md` §5.1 — a `FAILED`/`CANCELLED` payment does not count, so a new attempt with a
new key is accepted).

**Example.**

```ts
// features/registrations/api/requests.ts
import { request } from '@/shared/api/http-client'
import type { components, operations } from '@/shared/api/schema.gen'

type Registration = components['schemas']['Registration']
type CreateRegistrationRequest = components['schemas']['CreateRegistrationRequest']

export function createRegistration(body: CreateRegistrationRequest, idempotencyKey: string) {
  // Typed against the contract: a misspelled header name fails to compile.
  const headers: operations['createRegistration']['parameters']['header'] = {
    'Idempotency-Key': idempotencyKey,
  }
  return request<Registration>('/api/v1/registrations', {
    method: 'POST',
    headers: { ...headers, 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}
```

```ts
// features/registrations/api/mutations.ts
import { useRef } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { isTransient } from '@/shared/api/errors'
import { registrationKeys } from './keys'
import { createRegistration } from './requests'

export function useRegisterForWorkshop(workshopId: string, options?: { onSuccess?: () => void }) {
  const queryClient = useQueryClient()
  const intentKey = useRef<string | null>(null) // one key per intent, survives re-renders and retries

  const mutation = useMutation({
    mutationFn: (idempotencyKey: string) => createRegistration({ workshopId }, idempotencyKey),
    onSuccess: (registration) => {
      intentKey.current = null // intent ended
      queryClient.setQueryData(registrationKeys.detail(registration.id), registration) // read-after-write, §7
      void queryClient.invalidateQueries({ queryKey: registrationKeys.lists() })
      options?.onSuccess?.() // cross-feature effects are the route's job, §7
    },
    onError: (error) => {
      if (!isTransient(error)) intentKey.current = null // definitive (e.g. 409 sold-out): next submit is a new intent
      // transient (network, 429, 503, 409 idempotency-key-in-use): keep the key — a retry must replay or re-run the SAME intent
    },
  })

  return {
    register: () => {
      intentKey.current ??= crypto.randomUUID()
      mutation.mutate(intentKey.current)
    },
    isPending: mutation.isPending,
    error: mutation.error,
    data: mutation.data,
  }
}
```

---

## 11. Auth & Permission-gated UI

**Decision.**

- Sign-in calls `login` (`POST /api/v1/auth/login`). The response (`schemas/user.yaml#/LoginResponse`)
  carries `accessToken`, `expiresIn` (seconds) and `user` (`UserSummary`: `id`, `fullName`,
  `email`, `roles`). There is no refresh token and no refresh endpoint; an expired token means a
  new sign-in (`auth-and-identity.md` §4).
- **Permissions come from `LoginResponse.permissions`** — the same resolved codes the token
  carries (`auth-and-identity.md` §2, §7) — stored with the token in the session snapshot. The token
  is opaque to the client and is never decoded. The list is for UX only.
- Every UI check asks "does the session hold permission X" — `hasPermission(...)` /
  `RequirePermission` — **never** compares against a role name. `UserSummary.roles` is a display
  label only ("Signed in as: Organizer"), never an input to any condition.
- An operation listing several permissions in `x-required-permission` is satisfied by **any one**
  of them (OR, never AND — `packages/api-contract/README.md`). Guards take `anyOf`.
- Guards are **UX only.** The Backend API is the enforcement point: it re-verifies the token and
  checks `hasAuthority(...)` on every request (`architecture.md` §5.1, §5.2; `auth-and-identity.md`
  §3). Hiding a button is not security; a `403` must still be handled (§6).
- Route guards live in `app/router/guards/`: `RequireSession` for the `me/` area,
  `RequirePermission anyOf={…}` per page in `routes/admin/`. Inside a page, conditional UI
  (e.g. an "Edit" link on the workshop detail) is decided in the **route**, which passes a boolean
  prop into the feature component — a feature never imports `features/auth` (§2 rule 4).
- Permission constants are copied from the contract's `x-required-permission` values, only for
  codes the Web Application uses: `REGISTRATION_CREATE` (registering and paying for one's own registration), `WORKSHOP_CREATE`, `WORKSHOP_EDIT`,
  `REPORT_VIEW`, `STUDENT_SYNC_VIEW`, `STUDENT_SYNC_RESOLVE`. `CHECKIN_SCAN` guards mobile-only operations and is
  not used here. No other code is added until the contract adds it.
- On sign-in and on session end (sign-out, `401`), clear the query cache so no previous user's
  data — or an anonymous `404` for a `DRAFT` — survives into the next session.
- The session (token, `user`, `permissions`, expiry) lives in `sessionStorage` (§8; `auth-and-identity.md` §7): it survives a reload, not the tab; the expiry is checked at start-up. A strict CSP and no raw-HTML rendering are **preconditions**: if the CSP below is not in place, the token is kept in memory only.
- **Content-Security-Policy (Phase 2 checklist D14, C34).** Phase 2 ships it as a `<meta http-equiv="Content-Security-Policy">` tag injected **only into the production build** (a Vite `transformIndexHtml` hook with `apply: 'build'`), so dev HMR keeps working:
  `default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self' <API origin from build config>; object-src 'none'; base-uri 'self'; form-action 'self'`.
  No Stripe.js is loaded (Phase 2 uses test tokens), so no third-party origin is allowed. A build test asserts the tag is present with these directives and that `index.html` has no inline `<script>`. Before M-78 is ticked, every page is opened on `vite build && vite preview` and the browser console must show **no** CSP violation (Phase 7's Playwright suite automates this with a `securitypolicyviolation` listener). If a dependency must inject `<style>` at run time, the one permitted exception is `style-src 'self' 'unsafe-inline'` — **styles only**, recorded in the policy's comment with the dependency that needs it; `script-src` never gets an exception. **Done on 2026-10-26 (headless Chrome, 0 violations)** — and it found one: Zod 4 probes `Function("")` at start-up and Chrome reports the caught probe as a `script-src` `eval` violation, so `shared/config/zod.ts` sets `z.config({ jitless: true })` and re-exports `z`; **every file that builds a schema imports `z` from there, never from `'zod'`** (an ESLint `no-restricted-imports` rule enforces it). *(Changed 2026-11-10, Phase 8 task 7.5: it used to rely on `app/main.tsx` importing the module first, which stopped being true when the administration pages became lazy chunks and the bundler put a schema module in a shared chunk that runs before the entry chunk; the probe came back on `/login` and the Playwright CSP guard caught it. The import graph, not the order of imports, now carries the guarantee.)* Any library that evaluates code at run time is the same kind of finding: switch the feature off, do not widen `script-src`. A static search of the bundle does not replace the browser check (minified code reads `Function(`, not `new Function`). Hashes are not used (they break on every library upgrade) and nonces need a server (Phase 7 header) — Phase 2 checklist C38. Phase 7 moves the policy to an HTTP header at the serving layer, adding what a meta tag cannot carry — `frame-ancestors` "is not supported in the `<meta>` element" (MDN). **Decided 2026-11-05 (Phase 7 checklist D85):** the serving layer is the Web image's nginx (`apps/web/Dockerfile`, `deployment-and-cicd.md` §1), which sends the same policy as a header plus `frame-ancestors 'none'`; a test keeps the header and the build's policy equal, and the Playwright suite listens for `securitypolicyviolation` on every page. **Written 2026-11-05 (Phase 7 task 1.3):** the header is in `apps/web/nginx/default.conf` and `vite-plugins/__tests__/nginx-csp.test.ts` keeps it equal to `buildCsp('')` plus `frame-ancestors 'none'`; it is on `/`, a deep link, a hashed asset and a 404 (checked with `curl -sI` against the built image). The live check in a browser and the Playwright listener wait for Phase 7 task 9.4.

**Why.** `requirement.md` §1.2/§2.11 and `architecture.md` §5.1 make permissions, not roles, the
unit of access, so that roles can be reconfigured — or new ones added — without a code change. A
role-name check anywhere in the client would re-introduce the exact coupling the backend bans
(`backend-coding-standards.md` §11: `hasRole(` and role comparisons are banned). The token's
claim is what the Backend API itself enforces, so gating the UI from the same snapshot keeps what
the user sees aligned with what the server will allow, within the same 30-minute revocation lag
the design already accepts (`architecture.md` §5.2; `auth-and-identity.md` §4). Passing booleans
into features keeps features ignorant of the session, the same way backend use cases receive an
`Actor` rather than reading a security context. Clearing the cache on session change matters
because several reads depend on who is asking — `getWorkshop` reveals a `DRAFT` only to a caller
with `WORKSHOP_CREATE`/`WORKSHOP_EDIT`, and every "my …" endpoint is scoped to the caller.

**Example.**

```ts
// features/auth/model/permissions.ts — codes copied from x-required-permission in
// packages/api-contract/paths/*.yaml (openapi-typescript does not emit that extension, §5)
export const Permission = {
  REGISTRATION_CREATE: 'REGISTRATION_CREATE',
  WORKSHOP_CREATE: 'WORKSHOP_CREATE',
  WORKSHOP_EDIT: 'WORKSHOP_EDIT',
  REPORT_VIEW: 'REPORT_VIEW',
  STUDENT_SYNC_VIEW: 'STUDENT_SYNC_VIEW',
  STUDENT_SYNC_RESOLVE: 'STUDENT_SYNC_RESOLVE',
} as const
export type Permission = (typeof Permission)[keyof typeof Permission]
```

```ts
// features/auth/model/session.ts
import type { components } from '@/shared/api/schema.gen'
import type { Permission } from './permissions'

type LoginResponse = components['schemas']['LoginResponse']

export type Session = {
  accessToken: string
  expiresAt: number // epoch ms, from LoginResponse.expiresIn
  user: components['schemas']['UserSummary'] // display only; user.roles never gates anything
  permissions: ReadonlySet<string>
}

export function toSession(response: LoginResponse, now: number): Session {
  return {
    accessToken: response.accessToken,
    expiresAt: now + response.expiresIn * 1000,
    user: response.user,
    permissions: new Set(response.permissions), // UX only — the token itself is never decoded (§11)
  }
}

export const hasAnyPermission = (session: Session, anyOf: readonly Permission[]) =>
  anyOf.some((permission) => session.permissions.has(permission))
```

```tsx
// app/router/guards/RequirePermission.tsx
import type { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router'
import { hasAnyPermission, useSession, type Permission } from '@/features/auth'
import { NoPermission } from '@/shared/components/NoPermission'

export function RequirePermission({ anyOf, children }: { anyOf: readonly Permission[]; children: ReactNode }) {
  const session = useSession() // null when absent or past expiresAt
  const location = useLocation()
  if (!session) return <Navigate to="/login" state={{ from: location }} replace />
  if (!hasAnyPermission(session, anyOf)) return <NoPermission />
  return children
}
```

```tsx
// app/router/routes.tsx (excerpt)
{
  path: '/admin/workshops/new',
  element: (
    <RequirePermission anyOf={[Permission.WORKSHOP_CREATE]}>
      <CreateWorkshopPage />
    </RequirePermission>
  ),
},
```

---

## 12. Testing

**Decision.**

- **Component/unit:** Vitest + React Testing Library (`docs/testing/test-levels/e2e-testing.md`
  §1), jsdom environment. A test lives in a `__tests__/` folder inside the folder of the file it
  tests: `routes/public/__tests__/WorkshopDetailPage.test.tsx` tests
  `routes/public/WorkshopDetailPage.tsx`. The file keeps the `.test.ts(x)` suffix — Vitest's
  default `include` matches by name, not by folder — and imports its subject with `../`.
  A `*.test.ts(x)` file outside a `__tests__/` folder is a lint error (`apps/web/eslint.config.js`).
- **Network:** MSW mocks the Backend API at the network layer — never by mocking the http client
  or a feature's request functions. The MSW server, handlers, render utilities (e.g. rendering a
  route through the real route table) and test-data factories live in `src/testing/`. An
  unhandled request fails the test.
- Fixtures and handler bodies are typed with `components['schemas'][…]` from `schema.gen.ts`, so a
  contract change breaks the fixture at compile time instead of drifting silently.
- Each test gets a fresh `QueryClient` with query retries off.
- **E2E:** Playwright, in `tests/e2e/`, run pre-release against the Minikube deploy — not a
  per-push CI job (`e2e-testing.md` §2, §3). Installed since Phase 7 (§13 item 9).
- No frontend coverage threshold. The ≥80% target applies to backend core business-logic services
  (`proposal.md` §12; `requirement.md` §3.2).

**Why.** Mocking at the network layer exercises the real request functions, headers, error
normalization (§6) and cache behavior (§7) — the code most likely to break — instead of replacing
it. A `__tests__/` folder next to the code keeps tests in the same feature subtree as the file they
test — moved or deleted together, which matters in a layout organized by feature — while keeping
source folders listing only source. The `.test` suffix stays so Vitest's default discovery,
`boundaries/files`' test category (§2) and editors still recognize a test by its name. Without the
lint check, a test left beside its subject would still run, and the convention would erode
unnoticed.
Typing fixtures from the generated contract is the frontend's cheap equivalent of contract-diff.
A coverage number on UI code measures rendering, not the invariants the project's coverage gate
exists for.

**Example.**

```ts
// routes/public/__tests__/WorkshopDetailPage.test.tsx (excerpt) — a page test renders the real route table
import { http, HttpResponse } from 'msw'
import { server } from '@/testing/msw-server'
import { renderRoute } from '@/testing/render-route'
import { aWorkshop } from '@/testing/factories/workshop' // returns components['schemas']['Workshop']

it('shows seats left from the API response', async () => {
  server.use(http.get('/api/v1/workshops/:id', () => HttpResponse.json(aWorkshop({ capacity: 60, seatsTaken: 59 }))))
  renderRoute('/workshops/6c1f7e2a-0b3d-4a9e-9b1a-2f6e6b8c9d10')
  // …
})
```

---

## 13. Open Items

**Decision.** These are preconditions this file depends on but cannot decide. Each change belongs
to the document or code named, not to this file. Do not work around an open item in code — build
the part that doesn't depend on it, and ask.

**Why.** Same mechanism and reason as `backend-coding-standards.md` §27: a standard that silently
fills gaps with its own guesses becomes a second, conflicting source of truth.

**Example — the items.**

| # | Item | Owner (document or code) | Needed by |
|---|---|---|---|
| 1 | ~~**Boundary lint not configured.**~~ **Done (2026-10-13).** ESLint runs alongside `oxlint` for boundary rules only (`eslint-plugin-boundaries` 7.x, `apps/web/eslint.config.js`; §2 Enforcement). The lint script runs both. Run in CI by the `web` job of `.github/workflows/ci.yml` (Phase 2 checklist 5: `npm ci`, `api:types`, `typecheck`, `lint`, `test`); its first run on `main` was reported green by the project owner (2026-10-26) | `apps/web` | — |
| 2 | ~~**Code migration from the Mục 0 scaffold (and the Phase 1 Mục 2.4–2.5 pages) to §4.**~~ **Done (2026-10-13).** `apps/web/src` follows §4: `app/` (main, App, providers, router), `routes/public/`, `features/workshops/{api,components}`, `shared/{api,config,lib,ui}`, `styles/`, `testing/`; `components.json` aliases moved; unused `App.css` removed. `src/assets/` (unused Vite scaffold images) was left as is | `apps/web` | — |
| 3 | ~~**Web token storage & refresh strategy.**~~ **Done (2026-10-14).** `sessionStorage`, expiry checked at start-up, cleared on `401`/sign-out; no refresh token (`auth-and-identity.md` §7; §8, §11 here). CSP location open (Phase 2 checklist C34) | `auth-and-identity.md` §7 | — |
| 4 | ~~**Permission source for UI gating.**~~ **Done (2026-10-14).** `LoginResponse.permissions`; the token stays opaque (`schemas/user.yaml`, `auth-and-identity.md` §7; §11 here) | `packages/api-contract/schemas/user.yaml` | — |
| 5 | ~~**Machine-readable conflict reasons.**~~ **Done (2026-10-14).** One problem `type` URI per client-branching reason, a stable contract (`backend-coding-standards.md` §17, `common/error.yaml`; §6 here) | `packages/api-contract/common/error.yaml` | — |
| 6 | ~~**Payment `503` replay semantics conflict.**~~ **Done (2026-10-14).** `technical-design/modules/payment.md` §8 now follows `backend-coding-standards.md` §21: a `503` is never recorded under the key, so a same-key retry executes for real (Phase 2 checklist D1). §10 already followed this | `technical-design/modules/payment.md` §8 | — |
| 7 | ~~**Client timing values.**~~ **Done (2026-10-14).** Seats `staleTime` 3s / `refetchInterval` 5s while visible; status polling every 2s, stop at a final state (a payment owed a refund is not final until `REFUNDED`) or after 2 minutes; `503` auto-retry ≤ 3 with `Retry-After` + jitter, same key (§7, §10; Phase 2 checklist D15) | this file | — |
| 8 | ~~**Client-side payment collection flow.**~~ **Done (2026-10-14).** Phase 2 uses a dropdown of Stripe test tokens (success and decline), shown only with a test key; `paymentMethodToken` stays a generic opaque token so Stripe.js can be added later without an API change (Phase 2 checklist D11) | `technical-design/modules/payment.md` | — |
| 9 | ~~**E2E tooling not installed.**~~ **Done (Phase 7, 2026-11-06; extended in Phase 8).** Playwright is in `tests/e2e/` (its own package, not `apps/web`): `specs/` against a deployed cluster (`make e2e`), `specs-local/` against a production build (`npm run test:local`: the CSP spike, the design system, the screens, motion, worst-case data, touch rules, axe-core). Every spec of both folders fails on a `securitypolicyviolation` | `tests/e2e/README.md` | — |
| 10 | ✅ **Closed (Phase 8 Mục 3, 2026-11-09, C64)** — `listManagedWorkshops` (`GET /api/v1/admin/workshops`, `WORKSHOP_EDIT`, every status, paged) exists. *Was:* **No list of `DRAFT` (or `CANCELLED`/`COMPLETED`) workshops.** `listWorkshops` returns only `PUBLISHED` workshops regardless of caller, and no other endpoint lists workshops, so the `administration/` catalog cannot list drafts to edit or publish, nor past workshops to report on | `technical-design/modules/workshop.md`, `paths/workshop.yaml` | Phase 2 Organizer catalog UI |
| 11 | **QR rendering library.** ✅ **Decided (2026-10-30, Phase 5 checklist D56):** `qrcode.react` (SVG, version pinned), error-correction level **Q**, on the e-ticket page `/me/registrations/:id` in `features/registrations`. Fits the build's Content-Security-Policy (`img-src 'self' data:`; inline SVG needs no `img-src`). Installed 2026-11-01 (Phase 5 checklist task 7.2) as `qrcode.react` `4.2.0`, exact version. Measured at level Q: 260-character JWS, QR version 15 (`checkin.md` §5) | this file (§3, `registrations`) | Phase 5 |
| 12 | ~~**Permission on `createRegistration`.**~~ **Done (2026-10-14).** `REGISTRATION_CREATE` guards `createRegistration` and `chargePayment` (`backend-coding-standards.md` §11, §27 item 10); the `me/` checkout pages guard with `RequirePermission anyOf={[REGISTRATION_CREATE]}` | `auth-and-identity.md`, `user.md` | — |

---

## 14. Glossary & Naming

**Decision.** User-facing and code names come from the SRS and the contract, never coined. Status
values are used as the generated string-literal unions (`components['schemas']['RegistrationStatus']`
etc.), never re-declared.

| Term | Meaning | Source |
|---|---|---|
| Student | User group: discovers, registers, pays, holds the e-ticket. Not a permission | `requirement.md` §1.2 |
| Organizers/Admin (Organizing Committee, OC) | One user group: workshop administration, reports | `requirement.md` §1.2, §1.3.3 |
| Check-in Staff | User group of the mobile client only — no Web Application surface | `requirement.md` §1.2; `proposal.md` §5.1 |
| Role | A named bundle of permissions; display label only on the Web (`UserSummary.roles`) | `architecture.md` §5.1; `schemas/user.yaml#/UserSummary` |
| Permission codes | `WORKSHOP_CREATE`, `WORKSHOP_EDIT`, `REPORT_VIEW`, `STUDENT_SYNC_VIEW`, `STUDENT_SYNC_RESOLVE` (Web); `CHECKIN_SCAN` (mobile) | `x-required-permission` in `packages/api-contract/paths/*.yaml` |
| Workshop Administration | The `administration/` route area | `requirement.md` §1.3.3 |
| Workshop status | `DRAFT`, `PUBLISHED`, `CANCELLED`, `COMPLETED` | `schemas/workshop.yaml#/WorkshopStatus` |
| Registration status | `RESERVED` (seat held pending payment), `CONFIRMED`, `CANCELLED`, `EXPIRED` | `schemas/registration.yaml#/RegistrationStatus` |
| Hold | The 15-minute window of a `RESERVED` registration; ends at `holdExpiresAt` | `requirement.md` §1.3.1; `backend-coding-standards.md` §14.2 |
| Payment status | `PENDING`, `SUCCEEDED`, `FAILED`, `CANCELLED` | `schemas/payment.yaml#/PaymentStatus` |
| E-ticket | The QR code rendered from `Registration.qrPayload` (signed JWS); `ticketCode` identifies it | `schemas/registration.yaml#/Registration`; `architecture.md` §5.3 |
| Seat availability | `capacity - seatsTaken`; near-real-time, advisory | `schemas/workshop.yaml#/Workshop` |
| Intent | One user attempt at a guarded write; owns one `Idempotency-Key` (§10) | this file |

**Why.** One vocabulary from the SRS to the screen means a word in a bug report, a test name and a
component name mean the same thing — the rule `backend-coding-standards.md` §14.2 applies to the
backend.

**Example.**

```ts
import type { components } from '@/shared/api/schema.gen'

type RegistrationStatus = components['schemas']['RegistrationStatus'] // never a hand-written union

const statusLabel: Record<RegistrationStatus, string> = {
  RESERVED: 'Seat held — complete payment',
  CONFIRMED: 'Confirmed',
  CANCELLED: 'Cancelled',
  EXPIRED: 'Hold expired',
}
```

---

## 15. Design System

**Decision.** How the Web *looks* (colour tokens, type, spacing, motion, the state-is-never-colour-alone rule, and which UI libraries
may be used) is owned by `docs/guidelines/web-design-system.md`; this file keeps owning how the code is laid out.

- Shared presentational components live in `shared/ui/` and the design tokens in `src/styles/index.css`; neither knows a status, a
  permission code or a domain phrase (Rules #4). A status label is a map in the feature that owns the status (§14's example).
- Dark mode is the `.dark` class on `<html>` and nothing else; the choice lives in `shared/store/theme-store.ts` (a Zustand store of
  UI state with no business knowledge belongs in `shared/store/`, §8).
- **A new UI library is not added until it has passed the CSP spike** (`tests/e2e/specs-local/spike.spec.ts`, `npm run test:local` in
  `tests/e2e`) on all three engines. A library that would need a Content-Security-Policy exception is not added without the owner.
  Sonner, NumberFlow and Radix Select failed it (design-system doc §7).
- The Radix modal primitives get their scroll lock from `src/shared/lib/csp-safe-remove-scroll.tsx`, which `vite.config.ts` aliases in
  place of `react-remove-scroll`.

**Why.** The production policy is `style-src 'self'` without `'unsafe-inline'`, because the session token sits in `sessionStorage`
(§11). Libraries that inject a `<style>` element at run time fail silently in development, where there is no policy, and
visibly in the browser console in production; the spike makes that a build-time finding.
