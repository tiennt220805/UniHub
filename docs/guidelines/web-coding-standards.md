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
| `auth` | User | `User` | `login` | planned |
| `registrations` | Registration | `Registration` | `createRegistration`, `listMyRegistrations`, `getRegistration` (renders/downloads the QR e-ticket), `cancelRegistration`; reporting: `listWorkshopRegistrations` | planned |
| `payments` | Payment | `Payment` | `chargePayment`, `getPayment` | planned |
| `checkin` | Check-in | `Check-in` | `listWorkshopAttendances` (Organizer check-in reporting only) | planned |
| `notifications` | Notification | `Notification` | `listMyNotifications` | planned |
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

**Example.** The notification inbox is in the contract, so `notifications` is planned — but the
contract also states what it cannot provide (`schemas/notification.yaml#/Notification`): no
rendered text and no read/unread state. The feature therefore derives its display copy from
`NotificationType` and offers no "mark as read", rather than inventing either.

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
    administration/       # Workshop Administration: catalog management, reports, legacy-sync review
  features/
    <name>/
      api/                # requests.ts, queries.ts, mutations.ts, keys.ts (§7)
      components/
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
| `administration/` | `/administration` | the permission each page needs (`WORKSHOP_CREATE`, `WORKSHOP_EDIT`, `REPORT_VIEW`, `STUDENT_SYNC_VIEW`, `STUDENT_SYNC_RESOLVE`) | `requirement.md` §1.3.3 "Workshop Administration", §1.3.6/§1.5 (sync error review) |

**Naming.**

- Components: PascalCase file named after its single named export (`WorkshopCatalogPage.tsx`
  exports `WorkshopCatalogPage`). Route components keep the `…Page` suffix.
- Everything else: kebab-case (`http-client.ts`, `session-store.ts`, `workshop-form.schema.ts`).
  `shared/ui/` follows whatever the Shadcn generator emits (`button.tsx`).
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
ownership (`paths/registration.yaml#/registrations`). `administration/` is the SRS's own name for
the area (`requirement.md` §1.3.3, "Workshop Administration"), so it is used instead of a coined
one. There is no `utils/` next to `lib/` for the same reason as before: two general-helper
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
- The client does not generate `X-Correlation-Id`; the server always returns one, and errors carry
  it in the body (`common/headers.yaml#/CorrelationIdRequest`, `common/error.yaml`).
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
| `409` | conflict with current state (sold out, already registered, wrong status) | a clear, specific message for the operation; never retried with the same key (§10) | `proposal.md` §11.1, §12; `paths/registration.yaml#/registrations` |
| `429` | rate limit (per-user on `createRegistration`, per-IP on `login`, `listWorkshops`, `getWorkshop`) | no automatic retry; tell the user, and use `Retry-After` (seconds) for the wait | `architecture.md` §4.2; `technical-design/platform/distributed-coordination.md` §3; `common/headers.yaml#/RetryAfter` |
| `503` on `chargePayment` | payment gateway circuit open or timed out; seat hold untouched | an immediate message **inside the payment flow only** (the seat stays held for the rest of its 15 minutes); no app-wide outage UI — browsing keeps working | `proposal.md` §11.3; `requirement.md` §2.7; `technical-design/modules/payment.md` §3 |
| `503` on `createRegistration`/`cancelRegistration` | seat-lock contention or Redis down — transient, the seat may still be free | "try again shortly"; retry with the same key, honoring `Retry-After` (§10) | `technical-design/modules/registration.md` §2 |

The contract does **not** distinguish the two `409` causes on `createRegistration` (sold out vs.
already registered) by any machine-readable field: `type` is `about:blank` by convention and there
is no error code. Until it does (§13 item 5), the message must be correct for both causes.
User-facing copy is owned by the client, keyed by operation and status; `detail` is not shown as
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
  detail query's `staleTime`/`refetchInterval` for seats is not fixed by any source yet (§13
  item 7) — use TanStack defaults until it is.
- **Retry policy.** Queries never retry a `4xx`; other failures use TanStack's default retry.
  Mutations never retry automatically, except those guarded by `Idempotency-Key` (§10), which may
  be retried — manually by the user after any transient failure, or automatically only after a
  network failure or a `503` (honoring `Retry-After`; a `429` is never retried automatically, §6)
  — always reusing the **same** key. The automatic retry budget is §13 item 7.
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

// Memory only — provisional until §13 item 3 decides Web token storage. A reload signs the user out.
export const useSessionStore = create<SessionState>()((set) => ({
  session: null,
  start: (session) => set({ session }),
  end: () => set({ session: null }),
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
    `paymentMethodToken`). A key is never reused with a different body.
- **The intent continues — same key — after a transient failure:** no response (network), `429`,
  or `503`.
- Disable the submit control while `isPending`. That is UX only; the key is the guarantee.

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
(`paths/registration.yaml`, `paths/payment.yaml`). One known gap: `technical-design/modules/payment.md`
§8 records the circuit-open `503` under the key, which would make a same-key retry replay that
`503`; this conflicts with `backend-coding-standards.md` §21 (transient failures are not recorded)
and the contract's "safe to retry" (§13 item 6). If the component holding the key unmounts
mid-intent, the next mount starts a new intent; the database backstops still hold — at most one
active registration per user and workshop (the partial unique index behind
`createRegistration`'s `409`, `database-schema.md` §4.1) and at most one payment per registration
(`chargePayment`'s `409`, `database-schema.md` §5.1).

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
      if (!isTransient(error)) intentKey.current = null // definitive (e.g. 409): next submit is a new intent
      // transient (network, 429, 503): keep the key — a retry must replay or re-run the SAME intent
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
- **Permissions come from the token's `permissions` claim** — the resolved permission codes
  (`auth-and-identity.md` §2) — read once at sign-in into the session snapshot. The client decodes
  the claim without verifying the signature (it cannot: `HS256`, the secret is server-side only,
  `auth-and-identity.md` §3) and uses it for UX only. There is no session/"current user" endpoint
  in the contract, so the claim is the only source; reading it is isolated in one function in
  `features/auth/model/` so a change of source is a one-file change (§13 item 4).
- Every UI check asks "does the session hold permission X" — `hasPermission(...)` /
  `RequirePermission` — **never** compares against a role name. `UserSummary.roles` is a display
  label only ("Signed in as: Organizer"), never an input to any condition.
- An operation listing several permissions in `x-required-permission` is satisfied by **any one**
  of them (OR, never AND — `packages/api-contract/README.md`). Guards take `anyOf`.
- Guards are **UX only.** The Backend API is the enforcement point: it re-verifies the token and
  checks `hasAuthority(...)` on every request (`architecture.md` §5.1, §5.2; `auth-and-identity.md`
  §3). Hiding a button is not security; a `403` must still be handled (§6).
- Route guards live in `app/router/guards/`: `RequireSession` for the `me/` area,
  `RequirePermission anyOf={…}` per page in `administration/`. Inside a page, conditional UI
  (e.g. an "Edit" link on the workshop detail) is decided in the **route**, which passes a boolean
  prop into the feature component — a feature never imports `features/auth` (§2 rule 4).
- Permission constants are copied from the contract's `x-required-permission` values, only for
  codes the Web Application uses: `WORKSHOP_CREATE`, `WORKSHOP_EDIT`, `REPORT_VIEW`,
  `STUDENT_SYNC_VIEW`, `STUDENT_SYNC_RESOLVE`. `CHECKIN_SCAN` guards mobile-only operations and is
  not used here. No other code is added until the contract adds it.
- On sign-in and on session end (sign-out, `401`), clear the query cache so no previous user's
  data — or an anonymous `404` for a `DRAFT` — survives into the next session.
- Token storage beyond memory and persistence across reload are undecided (§13 item 3).

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
    permissions: new Set(readPermissionsClaim(response.accessToken)),
  }
}

/** UX only — unverified decode of the auth JWT's `permissions` claim (auth-and-identity.md §2). */
function readPermissionsClaim(jwt: string): string[] {
  const payload = jwt.split('.')[1] ?? ''
  const claims: unknown = JSON.parse(atob(payload.replace(/-/g, '+').replace(/_/g, '/')))
  const permissions = (claims as { permissions?: unknown }).permissions
  return Array.isArray(permissions) ? permissions.filter((p): p is string => typeof p === 'string') : []
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
  path: '/administration/workshops/new',
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
  §1), jsdom environment. Tests are colocated: `Foo.test.tsx` next to `Foo.tsx`.
- **Network:** MSW mocks the Backend API at the network layer — never by mocking the http client
  or a feature's request functions. The MSW server, handlers, render utilities (e.g. rendering a
  route through the real route table) and test-data factories live in `src/testing/`. An
  unhandled request fails the test.
- Fixtures and handler bodies are typed with `components['schemas'][…]` from `schema.gen.ts`, so a
  contract change breaks the fixture at compile time instead of drifting silently.
- Each test gets a fresh `QueryClient` with query retries off.
- **E2E:** Playwright, in `apps/web/e2e/`, run pre-release against the Minikube deploy — not a
  per-push CI job (`e2e-testing.md` §2, §3). **Not yet installed** (§13 item 9).
- No frontend coverage threshold. The ≥80% target applies to backend core business-logic services
  (`proposal.md` §12; `requirement.md` §3.2).

**Why.** Mocking at the network layer exercises the real request functions, headers, error
normalization (§6) and cache behavior (§7) — the code most likely to break — instead of replacing
it. Colocated tests move with the file they test, which matters in a layout organized by feature.
Typing fixtures from the generated contract is the frontend's cheap equivalent of contract-diff.
A coverage number on UI code measures rendering, not the invariants the project's coverage gate
exists for.

**Example.**

```ts
// routes/public/WorkshopDetailPage.test.tsx (excerpt) — a page test renders the real route table
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
| 1 | ~~**Boundary lint not configured.**~~ **Done (2026-10-13).** ESLint runs alongside `oxlint` for boundary rules only (`eslint-plugin-boundaries` 7.x, `apps/web/eslint.config.js`; §2 Enforcement). The lint script runs both. Not yet run in CI: `.github/workflows/ci.yml` has no `apps/web` job (Phase 1 Mục 4) | `apps/web` | — |
| 2 | ~~**Code migration from the Mục 0 scaffold (and the Phase 1 Mục 2.4–2.5 pages) to §4.**~~ **Done (2026-10-13).** `apps/web/src` follows §4: `app/` (main, App, providers, router), `routes/public/`, `features/workshops/{api,components}`, `shared/{api,config,lib,ui}`, `styles/`, `testing/`; `components.json` aliases moved; unused `App.css` removed. `src/assets/` (unused Vite scaffold images) was left as is | `apps/web` | — |
| 3 | **Web token storage & refresh strategy.** Refresh is decided — there is none (`auth-and-identity.md` §4). Undecided: where the Web keeps the access token (memory only, `sessionStorage`, `localStorage`; an `httpOnly` cookie would change the contract's `bearerAuth` scheme) and therefore whether a session survives a reload; and, since no "current user" endpoint exists, whether `UserSummary` is persisted with it. The mobile decision (Expo SecureStore, `architecture.md` §5.3, §5.4) does not apply to a browser. Provisional until decided: memory only (§8) | `auth-and-identity.md` (a Web-client subsection), then §8/§11 here | Phase 1 sign-in UI |
| 4 | **Permission source for UI gating.** The contract describes `LoginResponse.accessToken` as "opaque to the client", yet `LoginResponse` carries no permission list and there is no session endpoint; §11 therefore decodes the `permissions` claim. Either amend the contract wording to allow an unverified, UX-only read of the claim, or add the resolved permissions to `LoginResponse`/`UserSummary` (they are UI-convenience data in the sense of `auth-and-identity.md` §2) | `packages/api-contract/schemas/user.yaml`, `auth-and-identity.md` §2 | Phase 1 sign-in UI |
| 5 | **Machine-readable conflict reasons.** `createRegistration`'s `409` covers both "sold out" and "already registered"; `common/error.yaml` has no code field and `type` is `about:blank` by convention, so the client cannot show the specific message `proposal.md` §11.1/§12 call for. Proposed: distinct problem `type` URIs per conflict reason (same for `chargePayment`'s two `409` causes) | `packages/api-contract/common/error.yaml`, `paths/registration.yaml`, `paths/payment.yaml`; `backend-coding-standards.md` §17 | Phase 2 |
| 6 | **Payment `503` replay semantics conflict.** `technical-design/modules/payment.md` §8 records the circuit-open fallback response under the `Idempotency-Key`, so a same-key retry would replay the `503`; `backend-coding-standards.md` §21 rule 5 says transient failures ("dependency down") are not recorded, and `paths/payment.yaml` calls the `503` "safe to retry". §10 follows the contract (retry with the same key). Proposed: align `payment.md` §8 with `backend-coding-standards.md` §21 | `technical-design/modules/payment.md` §8 | Phase 2 (before the checkout UI) |
| 7 | **Client timing values** — none is fixed by any source: (a) seat-availability `staleTime`/`refetchInterval` on the workshop detail (lower bound: the server-side `seats` cache lives 3s, `workshop.md` §2); (b) `getPayment` poll interval and stop condition (`chargePayment` always returns `PENDING`, and no push mechanism exists — `paths/payment.yaml`); (c) the automatic retry budget for `503` on idempotency-guarded mutations | this file (§7, §10), once decided | Phase 2 |
| 8 | **Client-side payment collection flow.** `CreatePaymentChargeRequest.paymentMethodToken` is an inferred field, "an opaque token from the gateway's own client-side SDK"; no document fixes the flow and `apps/web` has no gateway SDK (`schemas/payment.yaml`; `e2e-testing.md` §2) | `technical-design/modules/payment.md` | Phase 2 |
| 9 | **E2E tooling not installed.** Playwright is decided (`e2e-testing.md` §2) but not in `apps/web/package.json`, and `apps/web/e2e/` does not exist | `apps/web` | Phase 7 (`e2e-testing.md` §3) |
| 10 | **No list of `DRAFT` (or `CANCELLED`/`COMPLETED`) workshops.** `listWorkshops` returns only `PUBLISHED` workshops regardless of caller, and no other endpoint lists workshops, so the `administration/` catalog cannot list drafts to edit or publish, nor past workshops to report on | `technical-design/modules/workshop.md`, `paths/workshop.yaml` | Phase 2 Organizer catalog UI |
| 11 | **QR rendering library.** Rendering `Registration.qrPayload` as a QR image is a "frontend concern" (`technical-design/modules/checkin.md` §4); no library is chosen or installed | this file (§3, `registrations`) | Phase 5 |
| 12 | **Permission on `createRegistration`.** The contract declares none (any authenticated user, ownership only); `backend-coding-standards.md` §27 item 10 still asks to name one. The `me/` area follows the contract (`RequireSession`); if a code is added, its guard changes | `auth-and-identity.md`, `technical-design/modules/user.md` (tracked as backend §27 item 10) | Phase 2 |

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
