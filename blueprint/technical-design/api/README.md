# API Documentation — OpenAPI Contract

This directory is the physical-level API contract completing `architecture.md`'s
container/module boundaries and `technical-design/modules/*.md`'s narrated
flows. Those sources establish *that* each module owns a set of HTTP
operations, *that* auth is a bearer JWT carrying resolved permissions, and
*that* specific writes are idempotent under retry — this directory is where
those become a concrete, machine-readable HTTP contract: paths, request/
response bodies, status codes, headers.

Nothing here overrides `architecture.md` or a `modules/*.md` file — where this
spec and those disagree, the narrative document is the authority and this
spec has a bug.

## Status: pilot, not complete

**Workshop**, **Registration**, **Payment**, and **Check-in** are drafted.
Workshop+Registration were the initial pilot (highest-risk flow: discovery →
seat reservation, `architecture.md` §4.2/§6.1). Payment+Check-in followed,
completing the core transactional path (register → pay → check in) —
`modules/payment.md` names itself "this system's second-highest-risk
correctness path after seat reservation," the same reasoning that picked the
first pair. **User and Notification are not yet drafted** —
`openapi.yaml`'s `tags` list marks them `x-status: planned` so the
aggregated spec doesn't silently read as complete. Extend this directory
module-by-module following the same structure, not by writing both
remaining modules into one large diff.

## Structure

```
api/
├── openapi.yaml        # root: info, servers, security, tags, $ref aggregation
├── common/              # shared components — auth scheme, error shape, headers, pagination
├── paths/{module}.yaml  # one file per module — its HTTP operations
└── schemas/{module}.yaml # one file per module — its request/response bodies
```

Mirrors the `platform/` + `modules/` split already used elsewhere in
`technical-design/` — a module's path/schema file is that module's contract,
`common/` is the cross-cutting mechanism every module shares (the same
relationship `auth-and-identity.md` or `distributed-coordination.md` has to
`modules/*.md`).

## Toolchain

No `package.json` is committed — this repo has no build scaffolding yet
(`CLAUDE.md`: "no application source code exists yet"), and introducing Node
tooling for a docs-only concern before implementation starts would be
scope creep. Run everything via `npx`:

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
| Permission modeling | Custom `x-required-permission: <code>` extension per operation — a single string for one required permission, or an array of codes (e.g. `[WORKSHOP_CREATE, WORKSHOP_EDIT]`) meaning **any one** of them satisfies the check (OR, never AND). An accompanying `x-required-permission-note` on the same operation explains the OR relationship in prose where the codes alone don't make it obvious. | OpenAPI's built-in `security` scope array is an OAuth2 concept; this system's permission codes (`WORKSHOP_CREATE`, `CHECKIN_SCAN`, ...) aren't OAuth2 scopes, and forcing them into that field would misrepresent the auth model documented in `auth-and-identity.md` §2. An operation with no `x-required-permission` requires only a valid token. |
| Correlation header | `X-Correlation-Id` request+response, optional on the way in | Matches `observability.md` §1 exactly — this directory doesn't define a second identifier. |
| Contract authority once implementation starts | **This hand-written spec stays authoritative.** When Spring Boot code exists, wire `springdoc-openapi` to generate a spec from the running application's annotations, and diff it against this one in CI — a mismatch is a build failure, not a silent drift. This spec is not superseded by the generated one; the generated one is a conformance check against it. | Chosen over letting `springdoc-openapi`'s generated spec become the source of truth after implementation begins, and over generating server stubs directly from this spec via `openapi-generator` (rejected: too rigid for the iteration speed expected once Payment/Check-in's edge cases get implemented). |

## Conventions for every path file

- Every operation has a unique `operationId` (`camelCase`, e.g. `createRegistration`) — required for client codegen (e.g. `orval` generating TanStack Query hooks for the React frontend) to produce usable function names.
- Every operation lists `401` (invalid/expired token) and, if `x-required-permission` is set, `403` (valid token, missing permission) — per the two-distinct-response-codes discipline `auth-and-identity.md` §5 and `registration.md` §2 both already apply to their own failure paths. **One documented exception:** `paymentGatewayWebhook` (`paths/payment.yaml`) sets `security: []` and an `x-auth-model: gateway-webhook-signature` extension instead — it authenticates the Payment Gateway itself via signature verification (`payment.md` §7), not a UniHub user, so `bearerAuth`/`401`/`403` don't apply to it. This is the only operation in the whole spec that overrides the default `security: [{bearerAuth: []}]` for a reason other than "public, no auth needed" (contrast with `listWorkshops`/`getWorkshop`, which use `security: []` because they're genuinely public).
- `x-required-permission` may be a single permission code or an array of codes; an array means **any one** of them satisfies the check (OR) — documented per-operation via `x-required-permission-note` where that isn't obvious from the codes alone.
- Every 2xx response declares `common/headers.yaml#/CorrelationIdResponse` under `headers: X-Correlation-Id`, mirroring the `CorrelationIdRequest` parameter every operation already accepts — the correlation id is always echoed back, on success or error, never only one or the other.
- Every non-2xx response body is `common/error.yaml`, referenced the same way in every module — no module invents its own error shape.
- Write operations that are idempotency-guarded (`registration.md` §4, `payment.md` §8) declare `common/headers.yaml#/IdempotencyKey` as a required parameter; no other operation does.
