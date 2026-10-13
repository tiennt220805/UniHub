# Phase Sign-Off Template — UniHub Test Reports

This file is the physical-level completion of `../../strategy/test-plan.md` §1's per-phase exit criteria and of `../../strategy/test-strategy.md` §6 (Traceability & Reporting), for the question those files leave open: *when a phase is believed done, what is the written record that its exit criteria were actually checked?* It is a reusable checklist, deliberately **not** an enterprise-style release sign-off (`test-plan.md`'s own framing is that phase gates, not calendar dates or approvers, are the unit of progress in a single-developer project). Nothing here overrides `test-plan.md` — where this file and it appear to disagree, `test-plan.md` is the authority and this file has a bug.

**Living template — blank fields are by design.** This file and `../test-matrix.md` are the only two files in `docs/testing/` allowed to carry unfilled content, because they record *progress*, not decisions. Every `___`, empty table row and unticked `☐` in §2–§6 is a field for whoever uses the template, not something left incomplete. **This file is never filled in itself**: it is copied once per phase (§1), and the copy is the record. Nothing in the template body records a real sign-off; §7 is an illustration and says so.

**Out of scope, and where it actually lives:** the exit criteria themselves — this template has a column to copy them into, and never restates them (`../../strategy/test-plan.md` §1); which INV rows belong to which phase (`../../strategy/test-plan.md` §3, `../../strategy/test-strategy.md` §2); how the ≥80% coverage gate is measured (`../../test-levels/unit-testing.md` §3); which CI stages exist at which phase (`../../strategy/test-strategy.md` §4, `../../../technical-design/platform/deployment-and-cicd.md` §5); the traceability rows the checklist points at (`../test-matrix.md`); the Phase 7 preconditions P1–P4 and the Phase 8 nine-row evidence rule (both are in `../../strategy/test-plan.md` §1 and are copied like any other criterion). Load-test result artifacts and coverage reports are CI artifacts and are not attached here (`../../strategy/test-strategy.md` §6).

**Citation convention:** bare filenames are resolved by folder — `test-plan.md`, `test-strategy.md` live in `../../strategy/`; `coding-standards.md` in `../../guidelines/`; `unit-testing.md` in `../../test-levels/`; `test-matrix.md` in `../`. `proposal.md` is at `../../../` (the `docs/` root); `CLAUDE.md` is at the repository root; `IMPLEMENTATION-READINESS.md` is at `../../`.

---

## Table of Contents

- [1. Purpose and When to Use](#1-purpose-and-when-to-use)
- [2. Header Fields](#2-header-fields)
- [3. Exit Criteria](#3-exit-criteria)
- [4. Universal Checklist](#4-universal-checklist)
- [5. Known Gaps and Deferred Items](#5-known-gaps-and-deferred-items)
- [6. Confirmation](#6-confirmation)
- [7. Example — Phase 1 (Illustrative Only, Not a Real Sign-Off)](#7-example--phase-1-illustrative-only-not-a-real-sign-off)

---

## 1. Purpose and When to Use

**Rationale:** `test-plan.md` §1's Guarantee says no phase is complete on functionality alone — each phase's exit criteria must be met before the next phase's work begins. A criterion that was "checked" only in the developer's head is indistinguishable, a month later, from one that was never checked. One reusable template, copied per phase, gives all eight phases the same evidentiary bar and keeps the record short enough to actually be filled in.

**Model:**

| Use it when | Do not use it for |
|---|---|
| A phase's work is believed done and the next phase is about to start (`test-plan.md` §1, Phases 1–8) | A release version, a deployment approval, or any external sign-off — none exists in this project |
| Re-checking a phase after a change that could have undone its exit criteria | Recording load-test numbers or coverage percentages — those are CI artifacts |
| | Deferring an invariant — §5 forbids it |

**How to use it:** copy sections 2–6 into a new file `release-sign-offs/phase-<N>.md` (`<N>` = 1…8 — this file's own naming decision; `test-plan.md` names only the folder), fill the copy, and leave this file untouched. There is one template for all eight phases; do not create eight templates, and do not hard-code one phase's criteria into it.

**Guarantees:** a phase is complete only when a filled copy exists in which every box in §3 and §4 is ticked, every deferral is in §5, and §6 is confirmed. A phase whose copy has an unticked box is not complete, whatever the code does.

---

## 2. Header Fields

**Rationale:** a sign-off must say which phase, when, and against which commit — "CI is green" (§4) is meaningless without a commit to be green on.

**Model:**

| Field | Value |
|---|---|
| Phase (1–8, `proposal.md` §15 / `test-plan.md` §1) | ___ |
| Phase name | ___ |
| Date | ___ |
| Commit SHA the checklist was run against | ___ |
| INV IDs in scope (the INV IDs column of this phase's row in `test-plan.md` §3) | ___ |
| Self-certified by | ___ |

**Guarantees:** one signer line only — this is a single-developer project, and a second line would describe an approver who does not exist.

---

## 3. Exit Criteria

**Rationale:** this template is shared by all eight phases, so it cannot carry any one phase's criteria; it carries the *slots* for them. The authoritative text is the **Exit criteria** cell of this phase's row in `test-plan.md` §1. Each cell is a run of semicolon-separated claims; each claim is one row here, so each can be ticked or not on its own.

**Model:** copy the criteria verbatim (do not paraphrase — a paraphrase can quietly weaken a claim), one per row, and cite the evidence that satisfies it: the `../test-matrix.md` row key (`M-nn`) plus the CI run, or the real `Class#method` name.

| # | Exit criterion (copy from `test-plan.md` §1, this phase's row) | Evidence (matrix row `M-nn`, CI run, or `Class#method`) | Met |
|---|---|---|---|
| 1 | | | ☐ |
| 2 | | | ☐ |
| 3 | | | ☐ |
| … | | | ☐ |

**Guarantees:** the number of rows equals the number of claims in the source cell — a criterion dropped while copying is a criterion never checked. Where the phase's row in `test-plan.md` §1 is followed by additional sign-off conditions (a preconditions table, a sign-off rule), those are criteria too and get their own rows.

---

## 4. Universal Checklist

**Rationale:** the checks below hold for every phase — they are the parts of "done" that do not change with the phase's feature scope. They exist so the per-phase criteria in §3 cannot be met while the suite around them is quietly degraded.

**Model:**

| # | Check | How to verify | Done |
|---|---|---|---|
| U1 | Every INV in scope has Status `Passing` in `../test-matrix.md` — none `Not started`, `Drafted` or `Flaky — investigate`. An invariant that `test-strategy.md` §2 marks *pending* (as of writing: INV-20) cannot be ticked while pending; its phase's exit criteria say what blocks it | Filter `../test-matrix.md` §2.2 by this phase's number and a non-`—` **Related INV**; read the Status column | ☐ |
| U2 | Every other matrix row of this phase is `Passing`, or is listed in §5 with a reason. No row is `Flaky — investigate` | Same filter, rows with `—` in **Related INV** | ☐ |
| U3 | Coverage ≥80% on the core business-logic services in this phase's scope (`test-strategy.md` §3–§4; `unit-testing.md` §3). If the phase adds no such service, write "N/A" and why | The `unit-test` job's `jacocoTestCoverageVerification` step is green (CI measures it; this file does not) | ☐ |
| U4 | Every CI stage that exists at this phase is green on the commit in §2 — stages 1–4 from Phase 1, stage 5 from Phase 7, plus the Gitleaks secret scan (`test-strategy.md` §4 says which stage exists when) | The workflow run for that SHA | ☐ |
| U5 | No unresolved `TODO`/`FIXME` in this phase's test code | `git grep -nE 'TODO\|FIXME' -- '*/src/test/*'` for the backend (`coding-standards.md` §1 fixes `src/test/java/com/unihub/<module>/`); for the web, `git grep -nE 'TODO\|FIXME' -- 'apps/web/src/*.test.ts' 'apps/web/src/*.test.tsx' 'apps/web/src/testing/*' 'apps/web/e2e/*'` (`web-coding-standards.md` §12: colocated tests, `src/testing/`, `e2e/`); the mobile test location is not yet fixed in the corpus, so name the paths searched. Zero hits, or each hit is an item in §5 | ☐ |
| U6 | No test backing an INV of this phase is skipped or weakened (`test-plan.md` §2's Guarantee) | `git grep -n '@Disabled'` over the same paths finds none on an INV-tagged test; no assertion relaxed relative to the criterion it proves | ☐ |
| U7 | `../test-matrix.md` is updated for **every** row of this phase — Status, and real `Class#method` names in **Test case(s)** (no `chưa viết` on a `Passing` row). Ticked by hand; nothing checks it automatically | Read this phase's rows | ☐ |
| U8 | `../test-matrix.md` §4 (Coverage Gaps) and §5 (Reference Table) are refreshed to this sign-off — they are snapshots (`../test-matrix.md` §3) | Recount from the Status column and update both | ☐ |

**Guarantees:** these eight checks apply unchanged to all eight phases; a phase may answer "N/A — <reason>" to U3 only, and only when it adds no core business-logic service. U1 is not negotiable and cannot be answered N/A.

---

## 5. Known Gaps and Deferred Items

**Rationale:** some work is legitimately not done when a phase closes — `test-plan.md` §2 orders it: non-invariant work of Phases 4–7 and non-critical-path features are deferred first, before any invariant is touched. Writing the deferral down, with its reason, is what stops it becoming a forgotten gap. It is **not** a way to legitimize skipping an invariant.

**Model:** one row per deferred item. **If there is nothing to defer, write "None".**

| # | What is not done | Matrix row(s) | Why it is acceptable to defer | Picked up in |
|---|---|---|---|---|
| 1 | | | | |

What may appear here: work that `test-plan.md` §2 places in its second or third priority tier, and matrix rows marked `⚠` (no planned test, `../test-matrix.md` §4.2). A deferral cites the matrix row it leaves open and, where one applies, the `IMPLEMENTATION-READINESS.md` item it depends on.

**This section must not contain any INV that is not `Passing` in `../test-matrix.md`.** A Known Gap that names one is not a deferral: it is a failed sign-off, and the phase is not complete. It also cannot be used to drop or soften a criterion from §3 — a criterion that cannot be met is an unmet criterion, not a gap.

**Guarantees:** every `Not started`, `Drafted` or `Flaky — investigate` row of this phase in `../test-matrix.md` is either `Passing` by the time §6 is signed or appears in this table — and none of them carries an INV.

---

## 6. Confirmation

**Rationale:** the record needs one deliberate act at the end, so that a half-filled copy cannot be mistaken for a finished one. It is a personal statement by the only person in the project, not a legal or formal approval.

**Model:**

> I have read the exit criteria for Phase ___ in `test-plan.md` §1, ticked every box in §3 and §4 above against the evidence cited, and listed every deferral in §5 — none of which is a Non-Negotiable Invariant.
>
> Self-certified by: ___  Date: ___

**Guarantees:** a copy whose §6 is unsigned is a draft. Nothing here is a formal release approval, and nothing outside this repository depends on it.

---

## 7. Example — Phase 1 (Illustrative Only, Not a Real Sign-Off)

**Rationale:** the template is easiest to misuse in §3 (copying criteria) and §5 (what may be deferred), so one filled copy shows both. **Every value below is invented to demonstrate the format.** No Phase 1 test exists yet (`CLAUDE.md`, Project Status), every Phase 1 row in `../test-matrix.md` is `Not started`, and nothing here may be cited as evidence that any criterion is met. The ticks are part of the illustration.

**Model** — a filled copy for Phase 1, criteria copied from `test-plan.md` §1's Phase 1 row:

| Field | Value |
|---|---|
| Phase | 1 |
| Phase name | Foundation |
| Date | `YYYY-MM-DD` *(example)* |
| Commit SHA | `<sha>` *(example)* |
| INV IDs in scope | INV-01, INV-13 (from `test-plan.md` §3) |
| Self-certified by | *(the developer)* |

| # | Exit criterion (from `test-plan.md` §1, Phase 1) | Evidence | Met |
|---|---|---|---|
| 1 | RBAC integration test (INV-01) proves permission-based (not role-name) checks against a real JWT + real Postgres, and rejects a tampered JWT attempting privilege escalation | M-01, M-02, M-03 `Passing` · `<Class#method>` *(example)* | ☑ |
| 2 | ≥80% coverage on User/Workshop core logic | `unit-test` job green on `<sha>` *(example)* | ☑ |
| 3 | CI blocks a merge on lint/unit/integration failure from Phase 1 onward, and on contract drift once the first OpenAPI output exists | M-14, M-17 `Passing` *(example)* | ☑ |
| 4 | Nightly-sync test proves bad records land in the error store and the batch completes | M-10 `Passing` *(example)* | ☑ |

| # | Check | Done |
|---|---|---|
| U1 | INV-01 and INV-13 rows all `Passing` | ☑ |
| U2 | Other Phase 1 rows `Passing`, except the two listed in §5 | ☑ |
| U3 | `jacocoTestCoverageVerification` green | ☑ |
| U4 | Stages 1–4 and Gitleaks green on `<sha>` | ☑ |
| U5 | `git grep` for `TODO`/`FIXME` over the Phase 1 test paths: zero hits | ☑ |
| U6 | No `@Disabled` on INV-01 / INV-13 tests | ☑ |
| U7 | Phase 1 matrix rows updated | ☑ |
| U8 | Matrix §4 and §5 refreshed | ☑ |

**Known gaps (example):**

| # | What is not done | Matrix row(s) | Why it is acceptable to defer | Picked up in |
|---|---|---|---|---|
| 1 | Cache-refresh-on-change tests for workshop data — no test is planned in `test-plan.md` §1 | M-08 (`⚠`) | Not an invariant; `test-plan.md` §2 places non-invariant work below the CI gate and the invariants in priority | Phase 2 |
| 2 | Nightly-sync impact on concurrent users — no test is planned | M-11 (`⚠`) | Not an invariant; not on the seat-reservation critical path | Phase 3 |

*An invalid deferral, for contrast:* adding M-09 or M-10 (both INV-13) to this table because "the nightly sync test isn't done yet" would make the sign-off void — INV-13 is a Phase 1 invariant and §5 may not hold it.

**Confirmation (example):** *"I have read the exit criteria for Phase 1 …"* — signed and dated in a real copy, unsigned here.

**Guarantees:** this example is never edited to record a real result; a real Phase 1 sign-off is a new file, `release-sign-offs/phase-1.md`.
