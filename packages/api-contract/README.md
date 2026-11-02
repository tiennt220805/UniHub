# @unihub/api-contract — OpenAPI Contract

This package is the physical-level API contract completing `architecture.md`'s
container/module boundaries and `docs/technical-design/modules/*.md`'s narrated
flows. Those sources establish *that* each module owns a set of HTTP
operations, *that* auth is a bearer JWT carrying resolved permissions, and
*that* specific writes are idempotent under retry — this package is where
those become a concrete, machine-readable HTTP contract: paths, request/
response bodies, status codes, headers.

Nothing here overrides `architecture.md` or a `modules/*.md` file — where this
spec and those disagree, the narrative document is the authority and this
spec has a bug.

**Lives outside `docs/`, deliberately.** Unlike the prose design corpus, this
contract is a code-adjacent, tooling-consumed artifact — linted/bundled by
Redocly CLI today, the source `apps/web` generates its TypeScript types from
(`openapi-typescript`, types only — no generated client,
`docs/guidelines/web-coding-standards.md` §5) — and the same for `apps/mobile`
(types only via `openapi-typescript` plus a thin `fetch` client; `orval` was
once planned and was dropped by Phase 5 checklist D57) — and the source
`apps/backend`'s own `springdoc`-generated spec is diff-checked against in CI.
Standard practice in a polyglot monorepo: the Java backend can't `import` an
npm package the way `apps/web`/`apps/mobile` will, so this only ever becomes a
real build dependency for the two TypeScript apps — the backend's
relationship to it stays a CI conformance check on the raw `openapi.yaml`,
never a code import.

## Status: all six modules drafted

Drafted in three pairs, each cross-checked against its `modules/*.md`
source and each pair reviewed before the next started: **Workshop +
Registration** (the highest-risk flow: discovery → seat reservation,
`architecture.md` §4.2/§6.1), then **Payment + Check-in** (the rest of the
core transactional path — `modules/payment.md` names itself "this system's
second-highest-risk correctness path after seat reservation"), then
**User + Notification** (identity/RBAC-adjacent and async-dispatch-adjacent
surfaces — deliberately last, since both are thinner HTTP surfaces than the
other four: User's RBAC data is migration-seeded rather than API-mutable,
see Key decisions below, and Notification exposes exactly one read
endpoint).

**"Drafted" does not mean every gap is closed** — several endpoints and
fields are explicitly flagged in-file as inferred (not narrated in any
`modules/*.md`), and several real gaps are stated rather than invented
around: first-login password setup for a
legacy-synced account has no designed mechanism anywhere in this corpus,
and no endpoint suspends or reactivates a user despite `auth-and-
identity.md` §4 naming this directory as where that belongs (both User —
`paths/user.yaml`'s `login` description). Search this directory for
"flagged as an assumption" / "flagged as a known limitation" / "unresolved
gap" to find every one of them — they're deliberately left visible in the
spec itself, not collected in a separate document that could drift out of
sync with it. (The two gaps Notification used to carry were settled on
2026-10-28, Phase 4 checklist D38: the inbox returns stored `title`/`body`,
and there is deliberately no read/unread state.)

## Structure

```
packages/api-contract/
├── openapi.yaml        # root: info, servers, security, tags, $ref aggregation
├── common/              # shared components — auth scheme, error shape, headers, pagination
├── paths/{module}.yaml  # one file per module — its HTTP operations
└── schemas/{module}.yaml # one file per module — its request/response bodies
```

Mirrors the `platform/` + `modules/` split used in `docs/technical-design/` —
a module's path/schema file is that module's contract, `common/` is the
cross-cutting mechanism every module shares (the same relationship
`auth-and-identity.md` or `distributed-coordination.md` has to
`modules/*.md`) — even though this package now sits outside `docs/` entirely
(see the root note above for why).

## Toolchain

No `package.json` is committed yet — this repo has no build scaffolding at
all as of this writing (`CLAUDE.md`: "no application source code exists
yet"), and introducing Node tooling before `apps/web`/`apps/mobile` exist to
actually consume it would be scope creep. This directory's `packages/`
placement is a forward-looking claim on its eventual role (an npm workspace
member once a root `package.json`/workspace config exists), not a statement
that the workspace wiring exists today. Run everything via `npx` until then:

```bash
# Lint every file for broken $ref, missing descriptions, style issues
npx @redocly/cli lint openapi.yaml

# Bundle the multi-file spec into one file — needed for tools that don't
# support multi-file $ref (Swagger UI, most codegen targets)
npx @redocly/cli bundle openapi.yaml -o dist/openapi.bundled.yaml

# Human-readable rendered preview
npx @redocly/cli preview-docs openapi.yaml
```

`dist/` is generated output — do not hand-edit it, and do not treat it as
the source of truth; `openapi.yaml` + its `$ref` tree is.

## Key decisions

| Decision | Choice | Why |
|---|---|---|
| Spec version | OpenAPI 3.1 | Full JSON Schema alignment — matches the Zod-based validation already chosen for the React frontend, and every tool in this toolchain supports it. |
| File layout | Multi-file, split by module | Mirrors the `platform/`+`modules/` convention already established; keeps a PR touching one module's contract from producing a diff across the whole spec. |
| URI versioning | `/api/v1/...` | Already the precedent set in `modules/registration.md` (`POST /api/v1/registrations`) and `modules/payment.md` (`POST /api/v1/payments/charge`) — this directory formalizes it, doesn't invent it. |
| Error shape | RFC 7807 Problem Details (`common/error.yaml`) | One error envelope for every module. Spring Boot 3.x's native `ProblemDetail` class produces this shape directly — no hand-rolled error mapper needed at implementation time. |
| Pagination | Offset/limit (`page`/`size`, `common/pagination.yaml`), not cursor/keyset | Simpler to implement and consume, and this system's scale (`docs/requirement.md` §1.4`, PoC-sized) doesn't yet justify keyset pagination's added complexity. Revisit as a deliberate decision if a list endpoint's row count later makes offset scans expensive — not something to preempt now. |
| RBAC role/permission management | **No API surface at all** — `roles`/`permissions`/`role_permissions` are Flyway migration-seeded data (`platform/deployment-and-cicd.md`'s `INSERT ... ON CONFLICT DO NOTHING` seed step), not runtime-mutable via this contract. | Confirmed by precedent, not assumed: the deployment doc's own migration already treats role→permission bundling as versioned schema data, consistent with `CLAUDE.md`'s "schema migrations are explicit and versioned... never rely on... runtime mutation" posture. Adding a CRUD API here would invent both new endpoints and a new permission-code family (e.g. `ROLE_MANAGE`) that no document specifies. |
| Permission modeling | Custom `x-required-permission: <code>` extension per operation — a single string for one required permission, or an array of codes (e.g. `[WORKSHOP_CREATE, WORKSHOP_EDIT]`) meaning **any one** of them satisfies the check (OR, never AND). An accompanying `x-required-permission-note` on the same operation explains the OR relationship in prose where the codes alone don't make it obvious. | OpenAPI's built-in `security` scope array is an OAuth2 concept; this system's permission codes (`WORKSHOP_CREATE`, `CHECKIN_SCAN`, ...) aren't OAuth2 scopes, and forcing them into that field would misrepresent the auth model documented in `auth-and-identity.md` §2. An operation with no `x-required-permission` requires only a valid token. |
| Correlation header | `X-Correlation-Id` on the **response** only. A client-sent request header is ignored (D65, 2026-11-02): the value is always the request's trace ID, and a client that wants to join a trace sends `traceparent`. | Matches `observability.md` §1 and the Correlation ID Identity ADR — this directory doesn't define a second identifier, and a client-chosen one would let two requests collide on purpose. |
| Rate limiting | `429` + shared `common/headers.yaml#/RetryAfter`, added only on the operations `distributed-coordination.md` §3 names by scope: `createRegistration` (per-user, 5/10s), `login` (per-IP global, 100/60s, "credential-stuffing against login"), `listWorkshops`/`getWorkshop` (per-IP global, "catalog scraping"). Not added blanket-wide across every operation. | §3 defines exactly two limiter scopes with named example targets — extending `429` to every operation would assert rate limiting exists everywhere, which `distributed-coordination.md` doesn't claim. `getWorkshop`'s inclusion is flagged in-file as an inferred extension of "catalog scraping," not verbatim from that section. |
| Contract authority once implementation starts | **This hand-written spec stays authoritative.** When Spring Boot code exists, wire `springdoc-openapi` to generate a spec from the running application's annotations, and diff it against this one in CI — a mismatch is a build failure, not a silent drift. This spec is not superseded by the generated one; the generated one is a conformance check against it. | Chosen over letting `springdoc-openapi`'s generated spec become the source of truth after implementation begins, and over generating server stubs directly from this spec via `openapi-generator` (rejected: too rigid for the iteration speed expected once Payment/Check-in's edge cases get implemented). |

## Conventions for every path file

- Every operation has a unique `operationId` (`camelCase`, e.g. `createRegistration`) — required for codegen to produce usable names (e.g. the `operations['createRegistration']` types `openapi-typescript` generates for the Web Application, or `orval`-generated functions for the mobile app).
- Every operation lists `401` (invalid/expired token) and, if `x-required-permission` is set, `403` (valid token, missing permission) — per the two-distinct-response-codes discipline `auth-and-identity.md` §5 and `registration.md` §2 both already apply to their own failure paths. **One documented exception:** `paymentGatewayWebhook` (`paths/payment.yaml`) sets `security: []` and an `x-auth-model: gateway-webhook-signature` extension instead — it authenticates the Payment Gateway itself via signature verification (`payment.md` §7), not a UniHub user, so `bearerAuth`/`401`/`403` don't apply to it. This is the only operation in the whole spec that overrides the default `security: [{bearerAuth: []}]` for a reason other than "public, no auth needed" (contrast with `listWorkshops`/`getWorkshop`, which use `security: []` because they're genuinely public).
- `x-required-permission` may be a single permission code or an array of codes; an array means **any one** of them satisfies the check (OR) — documented per-operation via `x-required-permission-note` where that isn't obvious from the codes alone.
- Every 2xx response declares `common/headers.yaml#/CorrelationIdResponse` under `headers: X-Correlation-Id`, — the correlation id is always returned, on success or error, never only one or the other. There is no request-side parameter: the server mints the value (D65).
- Every non-2xx response body is `common/error.yaml`, referenced the same way in every module — no module invents its own error shape.
- Write operations that are idempotency-guarded (`registration.md` §4, `payment.md` §8) declare `common/headers.yaml#/IdempotencyKey` as a required parameter; no other operation does.
