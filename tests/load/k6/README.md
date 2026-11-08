# K6 load scripts

Scripts for `docs/testing/non-functional/performance-load.md`. Outside `apps/` because they belong to no
deployable (Phase 3 checklist D30).

| File | What |
|---|---|
| `adversarial.js` | Scenario B: 500 VUs vs. the last 10 seats of one free workshop (M-36). `503` is retried with the same `Idempotency-Key`, honouring `Retry-After`, at most 10 sends |
| `seed.sql` | One PUBLISHED workshop with 10 seats left, the rest held by real registrations, plus 500 contenders |
| `mint-tokens.mjs` | One HS256 token per contender, signed offline with a secret taken from the environment |
| `verify.sql` | The pass condition: `seats_taken <= capacity` and `seats_taken` = rows holding a seat |
| `run-adversarial-smoke.sh` | All of the above, once, against a local stack |

Phase 3 runs this **once, locally, as a smoke check**. It is not an evidence gate (the gate is the
Testcontainers suite, M-28..M-32) and is not in CI. Phase 9 reruns the same script at full topology.
Start-up commands are at the top of `run-adversarial-smoke.sh`.

`tokens.json` and `ids.txt` are git-ignored; the JWT secret is only ever read from the environment (INV-18).
