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

**Model:** `contract-diff` proves the `springdoc`-generated spec (derived from actual controller annotations) matches `packages/api-contract`'s hand-written spec, for every operation (path + HTTP method) both specs currently describe, compared as the fully resolved shape each operation puts on the wire — prose (descriptions, summaries, examples) is not compared. It answers "does the code's actual HTTP surface match what's documented as the contract" — nothing about whether a given endpoint's *business logic* is correct (that's Unit/Integration's job) or whether a *decision table* like the RBAC one in `test-design-techniques.md` §3.4 is implemented right (also Unit/Integration). This is `test-strategy.md` §1's "Prove API contract consistency" objective specifically, not a proxy for the other eight rows in that table.

**Guarantees:** a green `contract-diff` job means the documented and implemented HTTP shapes agree for every operation both specs currently cover — it says nothing about correctness beyond that shape, and nothing about whether a *declared* response or header is actually produced: the controller can declare a `429`, or an `X-Correlation-Id` header, with annotations alone, and the job passes whether or not a rate limiter or correlation-ID filter exists behind them. Such declarations are listed next to the code that makes them (a comment on the controller method), not proved here. No INV row in `test-strategy.md` §2 is marked as proved by this level (by design — every INV row there is either Integration- or Unit-level, per the tables in this document's siblings).

---

## 2. Local Reproduction Before Pushing

**Rationale:** the check is one script, `apps/backend/scripts/contract-diff.sh`, that the `contract-diff` CI job and a developer run unchanged — not GitHub-Actions-only magic, and not a second copy of the steps to keep in sync. A developer should find a mismatch before pushing, not after CI runs it. The mechanism and the reasoning behind each step are `deployment-and-cicd.md`'s Contract-Diff Mechanism ADR; this section is what to run and what each step does.

**Model:**

```bash
# needs Java 21, Node >= 22.12 (npx @redocly/cli), Docker; `jq` is used if installed, otherwise its Docker image
bash apps/backend/scripts/contract-diff.sh
```

| # | Step | What it runs |
|---|---|---|
| 1 | Generate the spec from the controller annotations | `SPRING_DATASOURCE_URL=<any JDBC URL> UNIHUB_SECURITY_JWT_SECRET=<any string of 32+ bytes> ./gradlew generateOpenApiDocs --no-daemon` → `build/openapi.json`. The application context is real, so a `DataSource` URL and a JWT secret must exist (the app refuses to boot without one; the spec generation never verifies a token, so a throwaway value is enough); no database has to be listening, nothing connects. springdoc is pinned to OpenAPI 3.1 (`application.yml`), the contract's dialect |
| 2 | Bundle the hand-written contract into one JSON file | `npx --yes @redocly/cli bundle ../../packages/api-contract/openapi.yaml -o …/hand-written-full.json` |
| 3 | Keep only the operations already implemented (§4) | `jq` filters the bundled spec's `paths` to the (path, method) pairs the generated spec has |
| 4 | Normalise both sides | `npx --yes @redocly/cli bundle <spec> --dereferenced --remove-unused-components` (every `$ref` resolved, unused components dropped), then `jq` deletes `info`, `servers`, `tags`, `security`, `components.securitySchemes` and `format: "int32"` |
| 5 | Diff | `docker run --rm -v …:/specs tufin/oasdiff:latest diff hand-written.json generated-norm.json --flatten-allof --exclude-elements description,examples,summary,title --fail-on-diff` |

Two normalisations deserve a note, because each hides something on purpose:

- **`security` / `components.securitySchemes` are removed on both sides — temporarily.** The contract declares a global `bearerAuth` default; Spring Security is not wired in until RBAC/JWT (M-02, M-03), so the generated spec cannot describe it. While this holds, `contract-diff` does **not** prove the authentication scheme. It is tracked in `../reports/test-matrix.md` §4.4 and the removal in the script is the exit condition.
- **Only `int32` is stripped from `format`.** springdoc writes `format: int32` for every Java `int`; the contract writes plain `type: integer`. `int64` (a Java `long`) is still compared.

The script prints where it left the intermediate files (`hand-written.json`, `generated-norm.json`, …) — open them to see exactly what was compared. `CONTRACT_DIFF_DIR` chooses that directory.

**Guarantees:** a developer who runs the script locally sees exactly the failure (or pass) `contract-diff` will report in CI. The script has been exercised both ways against the real `GET /api/v1/workshops`: it passes on the aligned code, and narrowing `@Max(100)` to `@Max(99)` on `size` makes it fail with `max: from 100, to 99`.

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

**Resolution, now applied in `deployment-and-cicd.md` §5 and implemented by the script of §2:** step 3 filters the bundled hand-written spec down to only the (path, HTTP method) pairs already present in the generated spec, via `jq`, before the diff. A module with no controller yet contributes nothing to either side of the comparison; a module that *is* implemented still gets a full shape diff, and `--fail-on-diff`'s strictness is unchanged for anything actually built. The filter was first written per path. The first real controller (`GET /api/v1/workshops`) showed that is not enough: the contract also describes `POST /api/v1/workshops` on the same path, which has no controller yet, and a path-level filter kept it in the comparison. It now works per operation. It is still a filter, not a per-module bundle split — simpler than re-bundling `packages/api-contract`'s `paths/`+`schemas/` files individually, with the same effect (unbuilt modules and operations don't count against the job).

**Guarantees:** an undocumented operation (implemented in code but absent from the hand-written spec) is **not** hidden by this filter — the filter only removes hand-written-only operations, never generated-only ones — so `contract-diff` still fails on that case exactly as it did before the fix. `contract-diff` is now safe to treat as wired in from Phase 1, per `../strategy/test-plan.md` §1's original framing.

---

## 5. Reference Table

| Item | Value | Section |
|---|---|---|
| What's proved | Generated-vs-hand-written HTTP shape consistency, not business correctness | §1 |
| Local repro | `bash apps/backend/scripts/contract-diff.sh`: `generateOpenApiDocs` → `redocly bundle` (JSON) → `jq` operation filter → `redocly bundle --dereferenced --remove-unused-components` + `jq` normalise → `oasdiff diff --flatten-allof --exclude-elements description,examples,summary,title --fail-on-diff` | §2 |
| Temporarily not proved | The authentication scheme (`security`, `securitySchemes` removed on both sides) until M-02/M-03 — `../reports/test-matrix.md` §4.4 | §2 |
| CI job | `contract-diff`, gated by `integration-test`, gates `build-and-push` | §3 |
| Phased-rollout fix | `jq` filters the hand-written spec to already-implemented operations (path + method) before diffing — wired into `deployment-and-cicd.md` §5 (Contract-Diff Mechanism ADR) | §4 |
