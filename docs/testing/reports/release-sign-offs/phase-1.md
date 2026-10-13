# Phase 1 Sign-Off — Foundation

Copy of `phase-template.md` §2–§6 for Phase 1. Bare filenames follow that template's citation convention (`test-plan.md`, `test-strategy.md` in `../../strategy/`; `test-matrix.md` in `../`).

**Status: signed — Phase 1 complete.** Every box in §3 and §4 is ticked, every deferral is in §5, and §6 was signed by Nguyễn Thành Tiến on 2026-10-13.

---

## 2. Header Fields

| Field                                                                          | Value                                                                                                                             |
| ------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------- |
| Phase (1–8, `proposal.md` §15 / `test-plan.md` §1)                             | 1                                                                                                                                 |
| Phase name                                                                     | Foundation                                                                                                                        |
| Date                                                                           | 2026-10-13                                                                                                                        |
| Commit SHA the checklist was run against                                       | `28325ca` (CI stages 1–4 and `secret-scan-gitleaks` green, as reported by Nguyễn Thành Tiến; later commits change documentation only) |
| INV IDs in scope (the INV IDs column of this phase's row in `test-plan.md` §3) | INV-01, INV-13                                                                                                                    |
| Self-certified by                                                              | Nguyễn Thành Tiến                                                                                                                 |

---

## 3. Exit Criteria

Copied verbatim from the **Exit criteria** cell of `test-plan.md` §1, Phase 1 row — four semicolon-separated claims, four rows. Criterion 3 is the text as amended on 2026-10-13 for a solo, direct-push project (the original required CI to block a merge).

| #   | Exit criterion (copy from `test-plan.md` §1, this phase's row)                                                                                                                                                                                                                                                                                          | Evidence (matrix row `M-nn`, CI run, or `Class#method`)                                                                                                                                                                                                                                                                           | Met |
| --- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --- |
| 1   | RBAC integration test (INV-01) proves permission-based (not role-name) checks against a real JWT + real Postgres, and rejects a tampered JWT attempting privilege escalation                                                                                                                                                                            | M-01, M-02, M-03 `Passing` · `PermissionAuthorizationIT#permissionMissing_is403`, `PermissionAuthorizationIT#roleClaim_isNotHonored`, `JdbcUserPermissionQueriesIT#reconfiguringRolePermissions_changesResult_withoutCodeChange`, `AuthJwtDecoderTest#rejects_tamperedPayload`, `AuthJwtSecurityIT#protectedPath_rejectsBadToken` | ☑   |
| 2   | ≥80% coverage on User/Workshop core logic                                                                                                                                                                                                                                                                                                               | M-15 `Passing` · `jacocoTestCoverageVerification` in the `unit-test` job on `28325ca`: `user.application.usecase` 98.9%, `workshop.application.usecase` 100%, `workshop.domain.model` 100%                                                                                                                                        | ☑   |
| 3   | CI runs lint, unit and integration tests on every push to `main` from Phase 1 onward, and contract drift once the first OpenAPI output exists, each gate skipping the ones after it on failure and reporting the failure on the commit that caused it (no branch protection: solo project pushing straight to `main`, `../reports/test-matrix.md` §4.4) | M-14, M-17 `Passing` · `.github/workflows/ci.yml`: `lint` → `unit-test` → `integration-test` → `contract-diff`, each `needs:` the one before, on every push and PR to `main`; all green on `28325ca`. No branch protection, by decision (`test-matrix.md` §4.4)                                                                   | ☑   |
| 4   | nightly-sync test proves bad records land in the error store and the batch completes                                                                                                                                                                                                                                                                    | M-10 `Passing` · `RunNightlySyncIT#m10_badRecords_quarantined_batchCompletes`                                                                                                                                                                                                                                                     | ☑   |

**Criterion 3 — amended.** The original text ("CI blocks a merge on lint/unit/integration failure …") assumed branch protection. Nguyễn Thành Tiến chose not to enable it (solo project, direct pushes to `main`) and amended the criterion in `test-plan.md` §1 on 2026-10-13; the evidence above meets the amended text.

---

## 4. Universal Checklist

| #   | Check                                                                                                                | How to verify                                                                                                                                                                                            | Done |
| --- | -------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---- |
| U1  | Every INV in scope has Status `Passing` in `test-matrix.md`                                                          | INV-01: M-01, M-02, M-03 `Passing`. INV-13: M-09, M-10 `Passing`                                                                                                                                         | ☑    |
| U2  | Every other matrix row of this phase is `Passing`, or is listed in §5 with a reason. No row is `Flaky — investigate` | M-04 … M-07, M-12 … M-17 `Passing`; M-08, M-11 in §5                                                                                                                                                     | ☑    |
| U3  | Coverage ≥80% on the core business-logic services in this phase's scope                                              | `jacocoTestCoverageVerification` green on `28325ca` (see §3 row 2)                                                                                                                                       | ☑    |
| U4  | Every CI stage that exists at this phase is green on the commit in §2 — stages 1–4 plus the Gitleaks secret scan     | `lint`, `unit-test`, `integration-test`, `contract-diff`, `secret-scan-gitleaks` green on `28325ca`. `sast-semgrep` and `dependency-check` are non-blocking and not stages (`deployment-and-cicd.md` §5) | ☑    |
| U5  | No unresolved `TODO`/`FIXME` in this phase's test code                                                               | `git grep -nE 'TODO\|FIXME' -- '*/src/test/*' '*/src/integrationTest/*'` and `-- 'apps/web/src/*/__tests__/*' 'apps/web/src/testing/*' 'apps/web/e2e/*'`: zero hits. No mobile code exists (Phase 5)     | ☑    |
| U6  | No test backing an INV of this phase is skipped or weakened                                                          | `git grep -n '@Disabled'` over the same backend paths and `.skip(` / `xit(` / `xdescribe(` over `apps/web/src`: zero hits                                                                                | ☑    |
| U7  | `test-matrix.md` is updated for every row of this phase                                                              | All 17 Phase 1 rows carry real `Class#method` names or are `Not started` and deferred (M-08, M-11); no `chưa viết` on a `Passing` row                                                                    | ☑    |
| U8  | `test-matrix.md` §4 and §5 are refreshed to this sign-off                                                            | §4.1 (59 `Not started`), §4.2 (19 `⚠` rows), §4.4, §4.5 and §5 (Passing 15 · Not started 59) refreshed 2026-10-13                                                                                        | ☑    |

---

## 5. Known Gaps and Deferred Items

| #   | What is not done                                                                | Matrix row(s)                | Why it is acceptable to defer                                                                                                                                                                                    | Picked up in                                    |
| --- | ------------------------------------------------------------------------------- | ---------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------- |
| 1   | Low-change workshop data served from a Redis cache and refreshed on change      | M-08 (`⚠`)                   | Not an invariant, not on the critical path: without the cache reads stay correct, only slower; seat availability is never decided from a cached value. No Redis client exists in Phase 1 (`test-matrix.md` §4.5) | Phase 2                                         |
| 2   | Test proving the nightly sync does not disrupt users being served while it runs | M-11 (`⚠`)                   | Not an invariant, not on the critical path. The mechanism ships with M-09 (02:00 window, 500-row commits); only the proof is deferred (`test-matrix.md` §4.5)                                                    | Phase 3                                         |
| 3   | Branch protection on `main` (merge blocking)                                    | M-14 (merge-blocking clause) | Nguyễn Thành Tiến's decision: solo project, direct pushes to `main` (`test-matrix.md` §4.4); exit criterion 3 was amended to match (§3)                                                                                  | — (until a second contributor or a PR workflow) |

No INV appears in this table. Other non-INV items left open inside `Passing` rows are tracked in `test-matrix.md` §4.5: the cross-instance job lock (Phase 3, INV-09), the concurrent-run `UNIQUE (email)` race (Phase 3), the quarantine review endpoints, and `correlationId` in problem bodies (Phase 6).

---

## 6. Confirmation

> I have read the exit criteria for Phase 1 in `test-plan.md` §1, ticked every box in §3 and §4 above against the evidence cited, and listed every deferral in §5 — none of which is a Non-Negotiable Invariant.
>
> Self-certified by: Nguyễn Thành Tiến  Date: 2026-10-13
