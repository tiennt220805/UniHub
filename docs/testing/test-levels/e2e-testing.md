# End-to-End Testing — UniHub Test Levels

This file is the physical-level completion of `../strategy/test-strategy.md` §3's E2E row. Two decisions this file previously left open — the tool, and where E2E sits in CI — are both now made: Playwright (§2), run pre-release against the Phase 7 Minikube deploy, never per-push (§3). `test-strategy.md` §3/§4 and `../strategy/test-plan.md` §1 are updated to match; where this file and those two appear to disagree, those two are the authority and this file has a bug.

**Out of scope, and where it actually lives:** everything already decided for the other three levels (`unit-testing.md`, `integration-testing.md`, `contract-testing.md`); the concrete Playwright scenario scripts themselves, once written (a future addition — no frontend or backend code exists yet to write them against, per `CLAUDE.md`'s Project Status); the Minikube deploy mechanics `§3` cites (`../../technical-design/platform/deployment-and-cicd.md` §4/§6).

---

## Table of Contents

- [1. Scope: E2E vs. Frontend Unit/Component Testing](#1-scope-e2e-vs-frontend-unitcomponent-testing)
- [2. E2E Tool: Playwright (Decided)](#2-e2e-tool-playwright-decided)
- [3. CI Placement: Pre-Release Only, Tied to Phase 7 (Decided)](#3-ci-placement-pre-release-only-tied-to-phase-7-decided)
- [4. Reference Table](#4-reference-table)

---

## 1. Scope: E2E vs. Frontend Unit/Component Testing

**Rationale:** `../strategy/test-plan.md` §1's Phase 1 row describes "first Web component/unit tests for the Phase 1 endpoints (tool not yet chosen, same open item as E2E)" — bundling frontend component testing and true E2E as one open item. They are not the same test level: a React component test (no browser navigation, no backend, one component/hook in isolation) is a *frontend Unit test*, the TypeScript-side counterpart to `unit-testing.md`'s JUnit5+Mockito row — not a smaller version of E2E. Conflating them risks either under-specifying E2E (treating a component test as sufficient E2E coverage) or over-scoping component tests (waiting on an E2E tool decision to write a component test that needs none of it).

**Model:**

| Level | What it exercises | Needs a backend? | Needs a real browser? |
|---|---|---|---|
| Frontend Unit/Component (proposed: **Vitest + React Testing Library**) | One React component or hook in isolation | No | No (JSDOM) |
| True E2E (Playwright, §2 below) | A full user journey — Student registration→payment→e-ticket, or an Organizer dashboard flow | Yes, the real deployed stack | Yes |

**Decision for the frontend Unit/Component row:** Vitest + React Testing Library — not a new open decision, essentially the default pairing for a Vite-based React/TypeScript stack already fixed in `CLAUDE.md`, low-risk enough to treat as settled here rather than left open alongside true E2E. This un-bundles `../strategy/test-plan.md` §1's Phase 1 phrasing, which previously listed frontend component tests as "tool not yet chosen, same open item as E2E" — that file's Phase 1 row now names Vitest+RTL directly and no longer bundles the two.

**Guarantees:** Playwright (§2) and frontend component tests (Vitest+RTL) are independent decisions — a future change to either doesn't require revisiting the other, since they're a different row of the pyramid, closer in spirit to `unit-testing.md`'s "Small, no I/O" than to this file's "Large, full stack."

---

## 2. E2E Tool: Playwright (Decided)

**Rationale:** weighed against Cypress on two grounds specific to this codebase, not a generic tool comparison. First, multi-browser coverage including WebKit — `CLAUDE.md`'s frontend targets browsers generally, not one engine, and Playwright drives all three major engines natively where Cypress's WebKit support has historically lagged Chromium/Firefox. Second, the Payment Gateway charge flow: `packages/api-contract/schemas/payment.yaml`'s `chargePayment` request takes a `paymentMethodToken` — "an opaque token from the gateway's own client-side SDK (e.g. Stripe.js), never raw card data," per that schema's own description — which is the standard PCI-scope-avoidance pattern, **not** a redirect to a separate hosted checkout page. No document in this corpus (`payment.md`, `architecture.md`, `requirement.md`) describes a hosted-checkout redirect; the schema itself flags the client-side flow as "inferred... revisit when a design note fixes the client-side payment-collection flow." What *is* plausible under the standard Stripe.js/Elements pattern this schema points at is a cross-origin **iframe** for card collection embedded in the same page — the top-level page never navigates away, but the card fields themselves may live in a frame served from the gateway's own origin. Playwright's native cross-origin iframe support handles that pattern without the workarounds Cypress has historically needed for it — a real advantage, but a narrower and more precisely-scoped one than "crosses to a separate hosted page and back," which earlier drafts of this section claimed without a source confirming it.

**Model:** Playwright, with its TypeScript API (matching `CLAUDE.md`'s frontend stack directly, no transpilation step). Auto-waiting reduces flaky waits without hand-written polling — a real benefit specifically because this project has zero prior E2E-flakiness data to tune against: no recorded flaky-test history exists anywhere in this corpus, unsurprising since no test at any level has run yet, per `CLAUDE.md`'s Project Status.

**Guarantees:** every true-E2E scenario, once written, is authored against Playwright's API from the start — no dual-tooling migration cost, since no E2E test exists yet to migrate.

---

## 3. CI Placement: Pre-Release Only, Tied to Phase 7 (Decided)

**Rationale:** decided against the project's own real constraints, not a generic per-push-vs-pre-release debate. `deployment-and-cicd.md` §6 states this project has exactly three environments — Local Dev, CI Runner, Minikube PoC — "not a staged dev→cloud-staging→production pipeline"; there is no fourth, staging-only tier for an E2E job to run against. `proposal.md` §15 frames delivery as eight sequential phases, not a recurring release cadence — "release" in this corpus's own vocabulary is a phase sign-off (`../strategy/test-plan.md` §1 Phase 8's "results recorded... and a phase sign-off," `../reports/release-sign-offs/`), not a weekly/sprint rhythm. A per-push E2E job would require standing up a wholly new CI mechanism — a built frontend, a running backend, and a real browser, together, on every push — beyond anything `test-strategy.md`/`test-plan.md` ask for anywhere else in the pipeline, and `proposal.md` §16 already names single-developer bandwidth and "learning curve for infrastructure-heavy tooling" as risks to manage, not invite. `../strategy/test-plan.md` §1's Phase 7 row already has an existing deliverable this decision attaches to without inventing a new mechanism: "smoke test against the deployed full topology."

**Model:** the full Playwright suite runs as part of that existing Phase 7 smoke-test deliverable, against a fresh Minikube deploy (`make deploy-dev` / `make deploy-loadtest`, `../../technical-design/platform/deployment-and-cicd.md` §4/§6) — triggered manually, the same way every Minikube deploy in this project is triggered, never by `.github/workflows/ci.yml`'s `push`/`pull_request` events (`deployment-and-cicd.md` §5's job graph is unchanged by this decision). External providers stay stubbed even here, per `../guidelines/mocking-guidelines.md` §1's guarantee — a full-topology E2E run still never calls real Stripe/S3/etc.

**Guarantees:** a Phase 7 (and every phase after it) sign-off is not granted until the full Playwright suite passes against that phase's Minikube deploy — E2E is a phase-gate requirement from Phase 7 onward, never a per-push CI gate and never silently skipped at sign-off time. This mirrors exactly how Load testing (`../strategy/test-strategy.md` §4's Load row) is scheduled: an evidence gate tied to specific phases, not folded into the per-push pipeline.

---

## 4. Reference Table

| Item | Value | Section |
|---|---|---|
| Frontend Unit/Component tool | Vitest + React Testing Library — decided, applied in `../strategy/test-plan.md` §1 Phase 1 | §1 |
| True E2E tool | Playwright — decided, on multi-browser (WebKit) coverage and native cross-origin iframe support | §2 |
| CI placement | Pre-release only — tied to `../strategy/test-plan.md` §1 Phase 7's existing smoke-test deliverable, never per-push | §3 |
