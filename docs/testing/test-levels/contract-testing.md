# Contract Testing — UniHub Test Levels

This file is the physical-level completion of `../strategy/test-strategy.md` §3's Contract row, from a testing-workflow angle: what a contract-diff failure actually means, how a developer reproduces it locally before pushing, and the reasoning behind one fix this file's own analysis originally surfaced (§4) and `deployment-and-cicd.md`'s Contract-Diff Mechanism ADR now applies. The mechanism itself — tool choices, the exact CI job, the ADRs behind both — is fully specified in `deployment-and-cicd.md` §5 and its ADR Minis (CI Job Structuring, Coverage Verification Mechanism, Contract-Diff Mechanism); this file cites that specification rather than re-deriving it. Nothing here overrides `deployment-and-cicd.md` — where this file and that one appear to disagree, `deployment-and-cicd.md` is the authority and this file has a bug.

**Out of scope, and where it actually lives:** the `oasdiff`/`springdoc-openapi-gradle-plugin`/`redocly` tool decisions and their rationale (`deployment-and-cicd.md`'s Contract-Diff Mechanism ADR); the CI job graph and `needs:` wiring (`deployment-and-cicd.md` §5); the OpenAPI contract's own content and per-module organization (`packages/api-contract/README.md`).

---

## Table of Contents

- [1. What Contract Testing Proves (and Doesn't)](#1-what-contract-testing-proves-and-doesnt)
- [2. Local Reproduction Before Pushing](#2-local-reproduction-before-pushing)
- [3. Gradle/CI Wiring](#3-gradle-ci-wiring)
- [4. Phased Rollout vs. Full-Spec Diff (Resolved)](#4-phased-rollout-vs-full-spec-diff-resolved)
- [5. Reference Table](#5-reference-table)

---

## 1. What Contract Testing Proves (and Doesn't)

**Rationale:** this level is easy to mis-scope as "another correctness check" alongside Unit/Integration — it isn't. It proves *shape consistency* between two artifacts, not business-logic correctness.

**Model:** `contract-diff` proves the `springdoc`-generated spec (derived from actual controller annotations) matches `packages/api-contract`'s hand-written spec, byte-for-shape, for every path both specs currently describe. It answers "does the code's actual HTTP surface match what's documented as the contract" — nothing about whether a given endpoint's *business logic* is correct (that's Unit/Integration's job) or whether a *decision table* like the RBAC one in `test-design-techniques.md` §3.4 is implemented right (also Unit/Integration). This is `test-strategy.md` §1's "Prove API contract consistency" objective specifically, not a proxy for the other eight rows in that table.

**Guarantees:** a green `contract-diff` job means the documented and implemented HTTP shapes agree for every endpoint both specs currently cover — it says nothing about correctness beyond that shape, and no INV row in `test-strategy.md` §2 is marked as proved by this level (by design — every INV row there is either Integration- or Unit-level, per the tables in this document's siblings).

---

## 2. Local Reproduction Before Pushing

**Rationale:** every step `deployment-and-cicd.md` §5's `contract-diff` job runs is a plain local command, not GitHub-Actions-only magic — a developer should be able to run the exact same check before pushing, rather than discovering a mismatch only after CI runs it.

**Model**, verbatim from `deployment-and-cicd.md` §5's real job:

```bash
# 1. Generate the spec from the current controller annotations
./gradlew generateOpenApiDocs --no-daemon
cp build/openapi.json /tmp/generated.json

# 2. Bundle the hand-written multi-file spec into one file (JSON, so jq can filter it)
npx --yes @redocly/cli bundle packages/api-contract/openapi.yaml -o /tmp/hand-written-full.json

# 3. Filter the hand-written spec down to already-implemented paths only (§4)
jq --argjson impl "$(jq '.paths | keys' /tmp/generated.json)" \
   '.paths |= with_entries(select(.key as $p | $impl | index($p) != null))' \
   /tmp/hand-written-full.json > /tmp/hand-written.json

# 4. Diff them — same tool, same flag, as CI
docker run --rm -v /tmp:/specs tufin/oasdiff:latest diff \
  /specs/hand-written.json /specs/generated.json --fail-on-diff
```

**Guarantees:** a developer who runs these four commands locally sees exactly the failure (or pass) `contract-diff` will report in CI — no separate local-only tooling to keep in sync with the CI mechanism.

---

## 3. Gradle/CI Wiring

**Rationale:** pointer only — `deployment-and-cicd.md` §5 is authoritative.

**Model:**

| Job | Gated by | Gates |
|---|---|---|
| `contract-diff` | `integration-test` success | `build-and-push` |

**Guarantees:** `build-and-push` is structurally unreachable if `contract-diff` fails — `deployment-and-cicd.md` §5's own stated guarantee, not restated in full here.

---

## 4. Phased Rollout vs. Full-Spec Diff (Resolved)

**Rationale:** writing §§1–3 above against the real `contract-diff` command surfaced a conflict, since fixed in `deployment-and-cicd.md`'s Contract-Diff Mechanism ADR — recorded here so the reasoning behind that fix stays visible from the testing side, not just the CI-implementation side.

**The conflict, as originally found:** `packages/api-contract`'s hand-written spec already describes all six business modules — its own README states "Status: all six modules drafted" (Workshop + Registration, then Payment + Check-in, then User + Notification, per that file's own three-pair rollout narrative). The *generated* spec, by contrast, only ever contains whatever controllers actually exist in code at a given point in time — which, per `../strategy/test-plan.md` §1, starts at two modules (User, Workshop) in Phase 1 and grows module-by-module through Phase 5. Diffing the **entire** hand-written spec against the **entire** generated spec, as the job originally did, would report every not-yet-implemented module's paths as "missing" and fail the job unconditionally until all six modules existed — directly contradicting `../strategy/test-plan.md` §1's Phase 1 exit criterion, which implies `contract-diff` should *pass* once User/Workshop exist, not fail because the other four modules don't exist yet.

**Resolution, now applied in `deployment-and-cicd.md` §5:** the `contract-diff` job filters the bundled hand-written spec's `paths` down to only the paths already present in the generated spec, via `jq`, immediately before the `oasdiff diff` step. A module with no controller yet contributes nothing to either side of the comparison; a module that *is* implemented still gets a full shape diff, and `--fail-on-diff`'s strictness is unchanged for anything actually built. This is a path-level filter, not a per-module bundle split — simpler than re-bundling `packages/api-contract`'s `paths/`+`schemas/` files individually, and it produces the same effect (unbuilt modules don't count against the job).

**Guarantees:** an undocumented endpoint (implemented in code but absent from the hand-written spec) is **not** hidden by this filter — the filter only removes hand-written-only paths, never generated-only ones — so `contract-diff` still fails on that case exactly as it did before the fix. `contract-diff` is now safe to treat as wired in from Phase 1, per `../strategy/test-plan.md` §1's original framing.

---

## 5. Reference Table

| Item | Value | Section |
|---|---|---|
| What's proved | Generated-vs-hand-written HTTP shape consistency, not business correctness | §1 |
| Local repro | `generateOpenApiDocs` → `redocly bundle` (JSON) → `jq` path filter → `oasdiff diff --fail-on-diff` | §2 |
| CI job | `contract-diff`, gated by `integration-test`, gates `build-and-push` | §3 |
| Phased-rollout fix | `jq` filters the hand-written spec to already-implemented paths before diffing — wired into `deployment-and-cicd.md` §5 (Contract-Diff Mechanism ADR) | §4 |
