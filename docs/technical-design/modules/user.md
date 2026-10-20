# User Module — Technical Design

This file is the physical-level completion of `architecture.md` §5.1 (Fine-Grained RBAC Model) and §4.6 (Distributed Scheduled Job Coordination, as applied to the nightly student sync), together with `docs/requirement.md` §1.3.6/§2.10/§2.11. Those sources establish *that* the User module owns the three-way `User`–`Role`–`Permission` model, *that* the nightly legacy-student sync runs on exactly one instance per cycle, and *that* it processes data through a sequential validation pipeline that quarantines bad records instead of halting on them — but leave the pipeline's own internal mechanics, the quarantine store, and safety under a concurrent duplicate run as physical detail for this document. Nothing here overrides `architecture.md` — where this file and that one appear to disagree, `architecture.md` is the authority and this file has a bug.

**Read this file top to bottom to understand the User module end to end.** Where it uses a shared platform mechanism, it explains what that mechanism gives the User module specifically and links out for the mechanism's own full design — it does not re-derive Redis locking or JWT verification from scratch.

**Scope:** the RBAC data model this module owns — `users`, `roles`, `permissions`, and their junction tables — narrated at the rationale level, with the exact schema in `../platform/database-schema.md` §2 (§1); the nightly legacy-student-sync pipeline end to end — file ingestion, per-record validation/classification, the upsert into `users`, the run-bookkeeping table, and the error-quarantine table (§2–§8).

**Out of scope, and where it actually lives:** the Redis job-lock itself (key, 30-minute lease, `tryLock` mechanics — `../platform/distributed-coordination.md` §2); the exact `users`/`roles`/`permissions`/`legacy_sync_runs`/`legacy_sync_errors` column definitions (`../platform/database-schema.md` §2, referenced throughout, not repeated here — this file used to carry `legacy_sync_runs`/`legacy_sync_errors`' own `CREATE TABLE` DDL directly; it now narrates rationale only, consistent with how it always treated `users`/`roles`/`permissions`); what a resolved JWT looks like once this module's data feeds into one (`../platform/auth-and-identity.md`); RBAC/permission assignment for the *synced student accounts themselves* (distinct from the administrative permission codes this file proposes for quarantine review, §7); any transport mechanism other than the shared volume decided below.

File ingestion in this document targets a Kubernetes-mounted shared volume, not AWS S3 — `CLAUDE.md` scopes S3 exclusively to brochure PDFs, the one deliberate managed-cloud dependency in an otherwise local/self-hosted stack (see `../modules/workshop.md`). All SQL referenced below targets PostgreSQL (the Master), following the same explicit-versioned-migration discipline as `../platform/database-schema.md` (`architecture.md` §6.2) — nothing here is ORM auto-generated. The two run/error tables are declared parent-before-child in `../platform/database-schema.md` §2.6/§2.7 (`legacy_sync_runs` before `legacy_sync_errors`) specifically so their DDL runs top-to-bottom without a forward foreign-key reference.

---

## Table of Contents

- [1. RBAC Data Model — What This Module Owns](#1-rbac-data-model-what-this-module-owns)
- [2. Ingestion & File Handling](#2-ingestion-file-handling)
- [3. Pipeline Stages (Two-Pass)](#3-pipeline-stages-two-pass)
- [4. Validation & Duplicate Classification Rules](#4-validation-duplicate-classification-rules)
- [5. Sync Run Bookkeeping (`legacy_sync_runs`)](#5-sync-run-bookkeeping-legacy_sync_runs)
- [6. Error-Quarantine Table (`legacy_sync_errors`)](#6-error-quarantine-table-legacy_sync_errors)
- [7. Fault Isolation from Live Traffic](#7-fault-isolation-from-live-traffic)
- [8. Upsert Idempotency & Concurrent-Run Safety](#8-upsert-idempotency-concurrent-run-safety)
- [9. Reference Table](#9-reference-table)
- [10. Login Credentials in Phase 2 & First-Login Setup (Open)](#10-login-credentials-in-phase-2-first-login-setup-open)
- [API Surface](#api-surface)

---

## 1. RBAC Data Model — What This Module Owns

**Rationale:** `architecture.md` §5.1 requires access control built on permissions, never role names — a User is assigned one or more Roles, a Role bundles a set of Permissions, and every API-layer check asks "does this user have permission X." This module is the sole writer of all five tables that realize that model (`CLAUDE.md`'s module-ownership table): `users`, `roles`, `permissions`, and the `user_roles`/`role_permissions` junctions.

**What each table is for, briefly** (exact columns in `../platform/database-schema.md` §2):

- **`users`** — the identity backing every other module's `user_id` foreign keys. `legacy_student_id` and `password_hash` are both nullable specifically to accommodate the nightly sync below: a synced student row has no password yet (§8 explains why it's excluded from the sync's own `UPDATE`), and a directly-provisioned Organizer/Admin/Check-in Staff account has no `legacy_student_id` at all.
- **`roles`** — a named, reconfigurable bundle of permissions (e.g. `Organizer`, `Check-in Staff`). The name is a display/config label only — **never** checked at the API layer (`architecture.md` §5.1).
- **`permissions`** — one row per atomic capability (`WORKSHOP_CREATE`, `CHECKIN_SCAN`, `REPORT_VIEW`, `REGISTRATION_CREATE` — the Student bundle from Phase 2, `../platform/database-schema.md` §10.1 `V4` — ...). This is the literal string every `hasAuthority(...)` check in every module tests against.
- **`user_roles`** / **`role_permissions`** — the M:N junctions. Reassigning a role, or changing what a role can do, is a data change to these tables — never a code change or redeploy (`architecture.md` §5.1's central guarantee).

**What this module does *not* do:** resolve a user's permissions into a signed token, or verify that token on a later request — that's `../platform/auth-and-identity.md`'s job, reusing this module's data as input. The User module's responsibility ends at being the correct, current source of "who has what."

**Guarantee:** adding a role, or changing an existing one's permissions, is exclusively a write to this module's own tables — no other module's code, schema, or deploy is ever touched to make that change take effect (`architecture.md` §5.1).

---

## 2. Ingestion & File Handling

**Rationale:** `docs/requirement.md` §1.3.6 requires the export to be ingested "automatically... with no manual intervention required." Since S3 is off the table (per `CLAUDE.md`, scoped to workshop brochures only — `../modules/workshop.md`) and this is a local, self-hosted Minikube proof-of-concept, a shared PersistentVolume mounted by the Backend API pod running the job is the natural fit — the same in-cluster posture already used for PostgreSQL, Redis, and RabbitMQ.

**Model:**

| Decision | Value | Why |
|---|---|---|
| Mount | Shared Kubernetes PersistentVolume, mounted read-only at `/var/unihub/legacy-sync/inbound` on the pod running the job | A PV is the K8s-native way to hand a file to a pod without adding a protocol client (SFTP) the application doesn't otherwise need — consistent with keeping this dependency local rather than external. |
| Filename convention | `students_export_YYYYMMDD.csv` (the export's date, not the sync run's) | Lets the job unambiguously identify "tonight's file" by name rather than trusting file-system timestamps, and makes a missing file for a given date detectable as an absence, not a guess. |
| File format | CSV, UTF-8, header row required | Confirmed assumption — the simplest fit for a nightly flat-file export; no format was specified upstream in `docs/requirement.md` or `architecture.md`. |
| Missing file for tonight's date | Create one `legacy_sync_runs` row (§5) with `status = 'FAILED'`, `failure_reason = 'FILE_NOT_FOUND'`, `finished_at = now()`; log and surface an operational alert; the pod does not crash or retry mid-cycle | Same "skipped cycle, not a queued retry" posture `architecture.md` §4.6 already applies to lock contention — a missing export is recoverable by tomorrow's file. Recording the attempt in `legacy_sync_runs` (rather than only an out-of-band alert) is what makes §5's "every cycle leaves exactly one queryable record" guarantee true for *every* failure mode, not just the ones that get as far as opening the file. |
| File present but unreadable (bad encoding, unparsable header) | Whole-run failure: zero `users` writes, zero quarantine rows, one `legacy_sync_runs` row (§5) with `status = 'FAILED'`, `failure_reason = 'FILE_UNREADABLE'`, the parse error captured in `failure_detail`, alert | Distinct from a per-record validation failure (§4). A file that can't even be opened isn't "a handful of bad records" the pipeline can skip past — it's a fatal precondition, and partially processing a garbled file would be worse than processing none of it. |

```mermaid
flowchart LR
    Tick["Scheduled tick fires"] --> Lock{"unihub:lock:job:\nnightly-student-sync\nacquired?\n(../platform/distributed-coordination.md §2)"}
    Lock -->|no| SkipLock["Skip — another pod\nalready running this cycle"]
    Lock -->|yes| StartRun["INSERT legacy_sync_runs\nstatus = RUNNING (§5)"]
    StartRun --> FindFile{"students_export_\n{today}.csv present\non shared PV?"}
    FindFile -->|missing| MissingRun["UPDATE run: FAILED\nFILE_NOT_FOUND (§5), alert"]
    FindFile -->|present, unreadable| FailRun["UPDATE run: FAILED\nFILE_UNREADABLE (§5), alert"]
    FindFile -->|present, readable| Pipeline["Enter pipeline (§3)"]
```

**Guarantees:** a missing or corrupt export never crashes the pod and never partially applies a night's data — the outcome is always a cleanly recorded `legacy_sync_runs` row (§5), recoverable by the next scheduled export.

---

## 3. Pipeline Stages (Two-Pass)

**Rationale:** `docs/requirement.md` §2.10 requires "a sequential, step-by-step validation pipeline: verifying the format of each record before writing it into the primary system," with the explicit constraint that "a handful of faulty records must never be allowed to halt the entire synchronization process." §4's duplicate rule requires rejecting **every** occurrence of a duplicated key, including the first — which a single streaming pass cannot deliver: by the time a second occurrence is read, the first may already be committed to `users`. The pipeline is therefore **two-pass**, not one.

**Model:**

**Pass 1 — In-Memory Frequency Scan.** A single read-only pass over the file that touches only two columns, `legacy_student_id` and `email`, and never opens a database connection. It builds two in-memory frequency maps (value → occurrence count), one per column. This pass is cheap by construction — two short strings per row, not the full record — and its cost scales with file size alone, never with `users` table size or database load, so it adds no contention on the Master (§7).

**Pass 2 — Parse, Validate & Apply.** The per-row pipeline, reading Pass 1's frequency maps to classify duplicates from the very first row that carries a repeated key:

1. **Parse** — read the raw CSV row into a candidate record. A row whose column count doesn't match the header is a parse failure, classified `MALFORMED_ROW` (§4).
2. **Validate & Classify** — check required fields (§4), then look up the row's `legacy_student_id` and `email` in Pass 1's frequency maps (§4). Each row resolves to exactly one of two outcomes: **VALID** or **REJECTED** (with a reason code).
3. **Apply** — VALID rows go through the upsert (§8); REJECTED rows are written to `legacy_sync_errors` (§6) instead. A row is never written to both, and never to neither.

Because Pass 1 completes before Pass 2 applies anything, a key that appears more than once is rejected on **every** row that carries it — including the first — never just the second onward. There is no window in which an eventual duplicate's first occurrence has already been upserted before the duplication is known. Pass 2 still commits in small batches (§7), not one giant transaction and not one row per transaction, since it's the only pass that touches Postgres.

```mermaid
flowchart TB
    subgraph Pass1["Pass 1 — In-Memory Frequency Scan (no DB access)"]
        Scan["Read legacy_student_id + email\nfor every row in the file"] --> Freq["Build frequency maps:\ncount(legacy_student_id), count(email)"]
    end

    Freq ==> Pass2Start

    subgraph Pass2["Pass 2 — Parse, Validate & Apply"]
        Pass2Start["Raw CSV row"] --> Parse{"Parses into\nexpected columns?"}
        Parse -->|no| Reject1["REJECTED:\nMALFORMED_ROW"]
        Parse -->|yes| Validate{"Required fields\nvalid? (§4)"}
        Validate -->|no| Reject2["REJECTED:\nINVALID_FIELD"]
        Validate -->|yes| DupCheck{"count(legacy_student_id) > 1\nor count(email) > 1\nin Pass 1 map?"}
        DupCheck -->|yes, any occurrence\nincluding the first| Reject3["REJECTED:\nDUPLICATE_IN_FILE"]
        DupCheck -->|no| Upsert["Upsert into users (§8)"]
    end

    Reject1 & Reject2 & Reject3 --> Quarantine["INSERT legacy_sync_errors (§6)"]
    Upsert --> Tally["Increment run counters (§5)"]
    Quarantine --> Tally
```

**Guarantees:** every row in the file ends up in exactly one of two places — a `users` upsert or a `legacy_sync_errors` row — never silently dropped, never counted twice. A duplicated key is rejected on every occurrence, including the first, because classification (Pass 1) is fully resolved before any row is applied (Pass 2).

---

## 4. Validation & Duplicate Classification Rules

**Rationale:** `docs/requirement.md` §2.10 requires "invalid or duplicate records" to be separated from valid ones, but doesn't define either term precisely enough to implement — that precision, and how "duplicate" is actually detected (§3's Pass 1), is this file's job.

**Model:**

| Check | Outcome if failed | Reason Code | Notes |
|---|---|---|---|
| Column count matches header | REJECTED | `MALFORMED_ROW` | Parse-level (§3), before any field-level check runs. |
| `legacy_student_id` present, non-empty | REJECTED | `INVALID_FIELD` | The natural key the upsert conflicts on (§8) — a record without one can't be upserted at all. |
| `email` present, RFC-5322-shaped | REJECTED | `INVALID_FIELD` | `users.email` is `NOT NULL UNIQUE` (`../platform/database-schema.md` §2.1); catching a malformed value here keeps the failure inside the pipeline's quarantine flow instead of surfacing as an unhandled SQL error from the upsert. |
| `full_name` present, non-empty | REJECTED | `INVALID_FIELD` | `users.full_name` is `NOT NULL` (`../platform/database-schema.md` §2.1). |
| `legacy_student_id`'s Pass-1 frequency count is exactly 1 across the whole file | else REJECTED, on every row carrying that value | `DUPLICATE_IN_FILE` | Pass 1 (§3) counts every occurrence across the entire file before Pass 2 applies anything, so a repeated key is rejected on every row that carries it — including the first — never just the second onward. An upstream data-quality issue in the legacy export, not something UniHub should silently resolve by guessing which occurrence is correct. |
| `email`'s Pass-1 frequency count is exactly 1 across the whole file | else REJECTED, on every row carrying that value | `DUPLICATE_IN_FILE` | `users.email` is also `UNIQUE` (`../platform/database-schema.md` §2.1) — two different students can't legitimately share one email, so this is a second angle on the same data-quality problem, checked independently of the `legacy_student_id` count above. Without this check, a repeated email would reach the upsert (§8) and fail on the `email` unique constraint instead of the `legacy_student_id` one it's coded to handle — an unhandled error, not a clean quarantine. |

**Explicitly not a rejection:** a `legacy_student_id` that already exists as a `users` row **from a prior night's sync** is the expected common case — an existing student's `email`/`full_name` being refreshed — and proceeds straight to the upsert (§8). Pass 1's frequency maps are scoped to *this file only*; they say nothing about what's already in the database, so "already in the system" and "duplicated within tonight's file" are never conflated.

**Guarantees:** "invalid" and "duplicate" each have a precise, non-overlapping definition, fully resolved before any row is applied; neither is ever conflated with a record simply matching an existing account, which is ordinary nightly-sync behavior for the large majority of rows.

---

## 5. Sync Run Bookkeeping (`legacy_sync_runs`)

**Rationale:** every scheduled cycle — successful, failed for any reason, or unable to even find a file — must leave exactly one queryable record, so an administrator (or the Observability Stack) never has to reconstruct what happened from ephemeral pod logs. This table is presented ahead of the quarantine table (§6) because `legacy_sync_errors` rows reference it by foreign key — a row can't be quarantined against a run that doesn't exist in the database yet.

**Exact columns are in `../platform/database-schema.md` §2.6** — referenced throughout, not repeated here, the same discipline this file already applies to `users`/`roles`/`permissions` (§1). `failure_reason` follows the same `VARCHAR + CHECK`, never-a-native-`ENUM` convention as `legacy_sync_errors.reason_code` (§6) and every status column in `../platform/database-schema.md` §1 — `PIPELINE_ERROR` is reserved for a mid-run crash Pass 2 itself can't attribute to a specific file problem, distinct from the two file-level failures named in §2. `failure_detail` mirrors `legacy_sync_errors.reason_detail` (§6) for the same reason: the coded reason is what a query filters on, the free-text detail is what a human reads.

**Model — state transitions:**

```sql
-- Start of cycle, before Pass 1 opens the file (§2) — every attempt gets a row from this point on
INSERT INTO legacy_sync_runs (export_date, status)
VALUES (:exportDate, 'RUNNING')
RETURNING id;

-- Missing file (§2)
UPDATE legacy_sync_runs
SET status = 'FAILED', failure_reason = 'FILE_NOT_FOUND', finished_at = now()
WHERE id = :runId;

-- Unreadable file (§2)
UPDATE legacy_sync_runs
SET status = 'FAILED', failure_reason = 'FILE_UNREADABLE', failure_detail = :parseErrorMessage, finished_at = now()
WHERE id = :runId;

-- Successful completion, after Pass 2 finishes (§3)
UPDATE legacy_sync_runs
SET status = 'COMPLETED',
    finished_at = now(),
    rows_processed = :rowsProcessed,
    rows_created = :rowsCreated,
    rows_updated = :rowsUpdated,
    rows_quarantined = :rowsQuarantined
WHERE id = :runId;
```

Every path through §2 and §3 ends in exactly one of these three `UPDATE`s — there is no path that leaves a row permanently `RUNNING`.

`export_date` — not `started_at`'s calendar date — is what §2's filename convention keys off. Two runs recorded against the same `export_date` is exactly the accepted lease-overrun scenario (§8); this table doesn't prevent that, it records both runs honestly, each with its own row and its own counters, rather than merging them into one.

`rows_created` vs. `rows_updated` distinguishes which branch of the upsert (§8) fired per row — purely observational, it feeds no correctness guarantee.

**Retention:** kept indefinitely, never swept or deleted — the same posture `../platform/database-schema.md` §6.3 gives `audit_events`, for the same class of reason. A `legacy_sync_runs` row is the only durable record that a given night's ingestion happened at all; that history is what a periodic administrative audit of the legacy-sync pipeline — and, over time, of the university's own data-quality trends — needs to look back over. Unlike `idempotency_keys` or `event_publications` (`../platform/database-schema.md` §6.3), this table's value is in the accumulated history, not in the latest state, so a cleanup sweep would be actively destroying the thing the table exists to provide.

**Guarantees:** every scheduled cycle — successful, failed, or unable to find a file — leaves exactly one queryable `legacy_sync_runs` row, from the moment it starts, retained for as long as the system exists.

---

## 6. Error-Quarantine Table (`legacy_sync_errors`)

**Rationale:** `docs/requirement.md` §2.10 requires quarantined records to be "set aside for manual review." A structured table, not a log line, is what lets an administrator query and act on quarantined records rather than trawling logs.

**Exact columns are in `../platform/database-schema.md` §2.7** — referenced throughout, not repeated here, the same discipline this file already applies to `users`/`roles`/`permissions` (§1) and `legacy_sync_runs` (§5). `legacy_sync_runs` (§5) is declared first in that section specifically so `legacy_sync_errors.sync_run_id`'s `REFERENCES` clause resolves against a table that already exists when both `CREATE TABLE` statements run top-to-bottom.

- `raw_row` stores the full original CSV line verbatim — an admin reviewing a quarantined record must be able to see exactly what the legacy system sent, without re-opening a file that may already have been rotated off the shared volume.
- `reviewed` is a plain boolean, not a workflow state machine — this table's job is triage visibility, not a ticketing system. Assignment/resolution workflow, if ever needed, is a future extension this file doesn't build toward now.
- `ON DELETE RESTRICT` to `legacy_sync_runs` follows `../platform/database-schema.md` §1's history-protection convention — a run's quarantined records shouldn't be able to outlive the run record explaining when and why they were quarantined.
- No foreign key to `users`: a quarantined row, by definition, never produced a `users` write.

**Access control:** two new permission codes, following the `code` convention already established for `permissions` (`../platform/database-schema.md` §2.3, e.g. `WORKSHOP_CREATE`, `CHECKIN_SCAN`) — `STUDENT_SYNC_VIEW` (read quarantined records and run history) and `STUDENT_SYNC_RESOLVE` (toggle `reviewed`). Split into two rather than one, consistent with `architecture.md` §5.1's granularity principle (viewing and acting are separate capabilities elsewhere in the system, e.g. `REPORT_VIEW` vs. exporting a report) — an account might reasonably need visibility into sync health without needing to mark records resolved, or the reverse for a more operational role. Neither code is wired to a role in this document; role-to-permission assignment is data configuration, not something this physical design fixes (`architecture.md` §5.1).

**Trade-off — no reviewer identity or timestamp on `reviewed`:** this is a deliberate scope cut, not an oversight. Role/permission changes, registration, payment, check-in, and workshop lifecycle changes are the five categories `architecture.md` §5.5 audits; marking a quarantined sync record reviewed changes none of them, so this file doesn't force audit-log weight onto a data-quality triage action. Revisiting this is a deliberate future re-scope, not a bug being fixed here.

**Retention:** kept indefinitely, never swept — the same rationale as `legacy_sync_runs` (§5). These rows are the university's data-quality history for the legacy student-data pipeline, valuable as an accumulated record for periodic audit, not just as a short-lived operational queue. Marking `reviewed = true` does not trigger deletion: resolving a quarantined record fixes the underlying data-quality issue at the source (the next night's export correcting itself), it doesn't retroactively make the fact that a bad record was seen tonight stop mattering for audit purposes.

**Guarantees:** every quarantined record is queryable by run and by review status, permission-gated behind `STUDENT_SYNC_VIEW`/`STUDENT_SYNC_RESOLVE`, and retained indefinitely — carrying enough raw detail for a human to correct the source data without needing the original export file.

---

## 7. Fault Isolation from Live Traffic

**Rationale:** `docs/requirement.md` §1.3.6 requires that "processing this data must not disrupt or affect the system's operation for users currently being served." `architecture.md` §4.1 splits reads and writes specifically to shield the write path registration depends on from unrelated load — the nightly sync's own writes to `users` on the Master are exactly the kind of unrelated load that principle exists to isolate.

**Model:**

| Decision | Value | Why |
|---|---|---|
| Batch/commit size | Commit every 500 rows (Pass 2's parse + validate + upsert/quarantine as one batch) | One-row-per-transaction multiplies round-trip overhead across a full export; one-transaction-per-file holds `users`-table locks for the run's entire duration and forces a full-file rollback on one late, unrelated failure. 500 is a starting tuning value, not load-tested — same posture `../platform/distributed-coordination.md` §1 already takes toward its own untested connection-pool defaults; revisit once real export sizes are known. |
| Scheduling window | **02:00 Asia/Ho_Chi_Minh (UTC+7)**, a fixed, named IANA zone — not "pod local time" | `docs/requirement.md` §1.3.6 requires "nightly," not a specific hour — running during the trough of registration traffic minimizes write-path contention before batch size even matters. A Kubernetes pod's OS clock/timezone is a node-configuration detail, not something this design should depend on implicitly; pinning to the same zone UniHub's registration traffic pattern (`docs/requirement.md` §1.4) is itself reasoned about in keeps the schedule meaningful regardless of which node the job's pod lands on. |
| Write-path topology | Same Master, same `users` table any other write path touches — no separate replica or schema carved out for sync writes | A second write path would violate `architecture.md` §6.1's single-authoritative-writer principle for no real benefit; batching (above) is what actually bounds the impact, not topology. |

**Trade-off:** this does not guarantee *zero* contention — a sync batch committing during an unexpected registration surge could still momentarily compete for the same table's write lock. This is accepted, not engineered away: `docs/requirement.md` §1.4 scopes the registration-surge scenario around workshop-registration opening specifically, and the 02:00 Asia/Ho_Chi_Minh window is chosen precisely so the two almost never overlap in practice, rather than building dedicated resource isolation for a collision this design treats as unlikely.

**Guarantees:** no single sync transaction holds a `users`-table lock for longer than one batch's worth of work; a mid-run failure loses at most one uncommitted batch, never the whole night's already-committed progress.

---

## 8. Upsert Idempotency & Concurrent-Run Safety

**Rationale:** this section closes the exact gap `../platform/distributed-coordination.md` §2 flags and leaves open: "This job's lease (30 minutes) is a guess at normal runtime, not a guarantee... require the sync's own upsert logic... to be safe under a concurrent duplicate run regardless — [that file] cannot make this job idempotent on its own."

**Model:**

```sql
INSERT INTO users (id, email, full_name, legacy_student_id, status, created_at, updated_at)
VALUES (gen_random_uuid(), :email, :full_name, :legacy_student_id, 'ACTIVE', now(), now())
ON CONFLICT (legacy_student_id) DO UPDATE
SET email      = EXCLUDED.email,
    full_name  = EXCLUDED.full_name,
    updated_at = now()
WHERE users.email <> EXCLUDED.email OR users.full_name <> EXCLUDED.full_name;
```

This relies on the `UNIQUE (legacy_student_id)` constraint already declared on `users` (`../platform/database-schema.md` §2.1) — the same database-enforced-backstop pattern already used for `idempotency_keys` (`../platform/database-schema.md` §8.1) and the RBAC junction tables, applied here to a different natural key.

**Why this is safe under a concurrent overlapping run (the lease-overrun scenario):** each row's upsert is a single, self-contained atomic statement scoped to one `legacy_student_id` — it never reads then writes across two separate round-trips the way an application-level "check, then insert-or-update" would. Two pods processing the same file concurrently and both reaching the same row at nearly the same instant simply serialize at the database's unique-index level: whichever statement arrives first takes the row lock and commits; the second blocks briefly and then applies its own values — identical, since both pods are reading the same source file — on top. The two runs converge to the same final `users` state either way. There is no lost update, no duplicate row, and no constraint violation ever surfaced to the application.

`password_hash` and `status` are deliberately **excluded** from `DO UPDATE SET`:
- `password_hash` — a student's own later password-set action must never be silently reverted to `NULL` by a subsequent night's sync reprocessing the same row (`../platform/database-schema.md` §2.1 already establishes this column is set once, outside this pipeline).
- `status` — an administrator's `SUSPENDED` decision is a UniHub-side action this sync must never overwrite back to `ACTIVE` just because the legacy export still lists the student as enrolled.

**What this section does not need to solve:** two overlapping runs producing two different `legacy_sync_runs` rows (§5) for the same `export_date`. That's an expected, accepted consequence of the lease-overrun scenario, not something this file prevents — each run is recorded honestly on its own, rather than merged into one.

**Trade-off:** the `WHERE` clause on `DO UPDATE` is an optimization, not a correctness requirement — without it, every row still converges correctly, just with a redundant `updated_at` bump and a wasted write on every unchanged row, every night. Skipping the write when nothing actually changed keeps the common case (most students' data identical to the night before) cheap.

**Guarantees:** the upsert converges to the same final `users` state regardless of how many times it is applied for the same input row, and regardless of how many pods apply it concurrently — the property `../platform/distributed-coordination.md` §2 required this file to provide, delivered at the SQL statement level rather than by external coordination.

---

## 9. Reference Table

Every table, convention, and tuning parameter this document introduces, traced back to what it backs — the same purpose the closing tables in `../platform/database-schema.md` §11 and `../platform/distributed-coordination.md` §5 serve for their files.

| Name | Type / Value | Backs |
|---|---|---|
| `/var/unihub/legacy-sync/inbound` | Shared PV mount path | Local, self-hosted file ingestion (`docs/requirement.md` §1.3.6) — deliberately not S3, which `CLAUDE.md` scopes to brochures only |
| `students_export_YYYYMMDD.csv` | Filename convention | Unambiguous identification of "tonight's file" (§2) |
| Pass 1 — in-memory frequency scan | Design pattern, no DB access | Ensures a duplicated key is rejected on every occurrence, including the first (§3, §4) |
| `legacy_sync_runs` | Table, declared before `legacy_sync_errors` | Per-cycle bookkeeping; parent-before-child ordering lets both `CREATE TABLE` statements run top-to-bottom without a forward FK reference (§5) |
| `legacy_sync_runs.status` / `.failure_reason` | CHECK: `RUNNING`/`COMPLETED`/`FAILED`; `FILE_NOT_FOUND`/`FILE_UNREADABLE`/`PIPELINE_ERROR` | Every cycle outcome queryable in one place, including a missing file (§2, §5) |
| `legacy_sync_runs` / `legacy_sync_errors` retention | Indefinite, no cleanup sweep | Historical data-quality reconciliation and periodic audit (§5, §6) — same posture as `audit_events` (`../platform/database-schema.md` §6.3) |
| `legacy_sync_errors` | Table | Quarantine store for invalid/duplicate records (`docs/requirement.md` §2.10) |
| `legacy_sync_errors.reason_code` | CHECK: `MALFORMED_ROW` / `INVALID_FIELD` / `DUPLICATE_IN_FILE` | Precise invalid-vs-duplicate classification (§4) |
| `STUDENT_SYNC_VIEW` / `STUDENT_SYNC_RESOLVE` | Permission codes (proposed) | Permission-based access to quarantine review, never role-name-based (`architecture.md` §5.1) |
| `ON CONFLICT (legacy_student_id) DO UPDATE` | Upsert statement | Idempotent upsert, safe under a concurrent duplicate run (§8) — the gap `../platform/distributed-coordination.md` §2 left open |
| Batch commit size (500 rows) | Tuning parameter | Fault isolation from live registration traffic (§7, `docs/requirement.md` §1.3.6, `architecture.md` §4.1) |
| 02:00 Asia/Ho_Chi_Minh (UTC+7) scheduling window | Tuning parameter | Minimizes overlap with the registration-surge scenario (§7, `docs/requirement.md` §1.4); pinned to a named zone rather than pod-local time |
| `unihub:lock:job:nightly-student-sync` | Redis lock (reference only) | Exactly-one-instance-per-cycle — decided in `../platform/distributed-coordination.md` §2, not redeclared here |

---

## 10. Login Credentials in Phase 2 & First-Login Setup (Open)

**Rationale:** Phase 2 needs a Student who can sign in and register, but a legacy-synced student has `password_hash = NULL` (§1, §8), and no document designs how such a student sets a first password — doing it properly needs an e-mail channel, which arrives with Notification in Phase 4. Decision: Phase 2 checklist D9 (Q7), 2026-10-14.

**Model:**

| Decision | Value |
|---|---|
| Phase 2 test accounts | A dev/test-only seeder creates a few Student accounts with a password and the `Student` role. It runs only under the `dev` profile (tests create their own users through fixtures). It never lives in `src/main/resources/db/migration` — no production migration ever creates them |
| The password | Read from an environment variable when the seeder runs and hashed with BCrypt there. Neither a plaintext password nor a fixed hash is committed; the Gitleaks gate (M-13) stays green. A missing variable stops the seeder with a clear message — there is no default password |
| Idempotency | Re-running the seeder creates nothing new; an account that already exists (same email) is left untouched, including its password hash |
| How it is wired | `DevStudentSeedRunner` (`@Profile("dev")`) → `SeedDevStudentsUseCase`. Password from `UNIHUB_DEV_SEED_STUDENT_PASSWORD` (`unihub.dev-seed.student-password`); with it unset or blank the runner logs a warning and seeds nothing. Accounts default to `student1..3@dev.unihub.local`, overridable via `unihub.dev-seed.students` |
| Legacy-synced students | Cannot sign in until first-login setup exists; login treats them exactly like a wrong password (`../platform/auth-and-identity.md` §7) |
| First-login setup | **Open item** — to be designed when an e-mail channel exists (Phase 4 or later); tracked in the Phase 2 checklist |

**Guarantee:** no environment other than a developer's or a test's ever contains an account that was not created through a designed path.

---

## API Surface

The concrete HTTP contract for every endpoint this module exposes is specified in `../../../packages/api-contract/paths/user.yaml` (schemas in `../../../packages/api-contract/schemas/user.yaml`), not repeated here — including the `legacy_sync_runs`/`legacy_sync_errors` read/resolve endpoints (§5, §6) verbatim. Where the two disagree, this file is the authority and the OpenAPI contract has a bug. Three things are flagged there as explicitly *not* covered, and are equally true of this file: `login`'s literal route (this file never names one, only `../platform/auth-and-identity.md` §4's reference to "the login endpoint"); first-login password setup for a legacy-synced account (`password_hash IS NULL` per `../platform/database-schema.md` §2.1) has no designed mechanism anywhere in this corpus; and RBAC role/permission management has **no HTTP surface at all** — it's Flyway migration-seeded data (`../platform/deployment-and-cicd.md`), not an API-mutable resource, per `../../../packages/api-contract/README.md`'s Key Decisions. See that README for the toolchain.
