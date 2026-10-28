// Scenario B — adversarial: 500 VUs vs. the last 10 seats of one workshop
// (docs/testing/non-functional/performance-load.md §2.2; Phase 3 checklist D30, M-36).
//
// A SMOKE CHECK, not an evidence gate: Phase 3's gate is the JUnit/Testcontainers suite (M-28..M-32).
// Phase 8 reruns this same script at full topology. K6 only ever exercises the full-stack variant.
//
// One iteration per VU. Each VU posts `createRegistration` for the one workshop with a key of its own;
// a 503 is retried after its Retry-After with the SAME Idempotency-Key until a definitive answer, at
// most MAX_SENDS times. A VU still without a definitive answer counts as `undecided`, never as a 409.
// The pass condition is the SQL invariant in verify.sql; the counters here must agree with it.
//
// Env (run-adversarial-smoke.sh sets them): BASE_URL, WORKSHOP_ID, TOKENS_FILE, WINNERS, CONTENDERS.
import http from 'k6/http';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { Counter } from 'k6/metrics';
import { sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const WORKSHOP_ID = __ENV.WORKSHOP_ID;
const WINNERS = Number(__ENV.WINNERS || 10);
const CONTENDERS = Number(__ENV.CONTENDERS || 500);
const MAX_SENDS = 10; // Phase 3 checklist D30

if (!WORKSHOP_ID) {
  throw new Error('WORKSHOP_ID is required');
}

// Minted offline just before the run (mint-tokens.mjs); never committed (INV-18).
const tokens = new SharedArray('tokens', () => JSON.parse(open(__ENV.TOKENS_FILE || './tokens.json')));

// The final status of each VU, after retry-until-definitive.
const outcome = new Counter('outcome');
// What each VU was told the very first time. Classified, never asserted as "all 409" (§2.2).
const firstResponse = new Counter('first_response');
const sends = new Counter('sends');
// 401/403 means the seeded tokens or permissions are wrong: it invalidates the run (§4).
const harnessErrors = new Counter('harness_errors');
const missingRetryAfter = new Counter('retry_after_missing_on_503');

export const options = {
  scenarios: {
    adversarial: {
      executor: 'per-vu-iterations',
      vus: CONTENDERS,
      iterations: 1,
      maxDuration: '2m',
      exec: 'contend',
    },
  },
  thresholds: {
    // exactly the remaining seats, and everyone else sold out, once every VU has a definitive answer
    'outcome{status:201}': [`count==${WINNERS}`],
    'outcome{status:409}': [`count==${CONTENDERS - WINNERS}`],
    'outcome{status:undecided}': ['count==0'],
    harness_errors: ['count==0'],
    'retry_after_missing_on_503': ['count==0'],
    // proposal.md §12: sub-second p95 on the registration endpoint (recorded, judged client-side)
    'http_req_duration{name:createRegistration}': ['p(95)<1000'],
  },
};

// RFC 4122 v4 from Math.random: the key only has to be unique per VU, not unguessable.
function uuidv4() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

export function contend() {
  const token = tokens[exec.vu.idInTest - 1];
  const key = uuidv4(); // one key for every send of this VU
  const params = {
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
      'Idempotency-Key': key,
    },
    tags: { name: 'createRegistration' },
  };
  const body = JSON.stringify({ workshopId: WORKSHOP_ID });

  let final = 'undecided';
  for (let attempt = 1; attempt <= MAX_SENDS; attempt++) {
    const res = http.post(`${BASE_URL}/api/v1/registrations`, body, params);
    sends.add(1);
    if (attempt === 1) {
      firstResponse.add(1, { status: String(res.status) });
    }
    if (res.status === 401 || res.status === 403) {
      harnessErrors.add(1);
    }
    if (res.status !== 503) {
      final = String(res.status);
      break;
    }
    const retryAfter = Number(res.headers['Retry-After']);
    if (!retryAfter) {
      missingRetryAfter.add(1);
    }
    sleep(retryAfter || 1);
  }
  outcome.add(1, { status: final });
  // a zero sample so a threshold on `undecided` has something to evaluate when no VU is undecided
  outcome.add(0, { status: 'undecided' });
}
