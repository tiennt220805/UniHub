# tests/e2e — Playwright suite for the Minikube deploy

Phase 7 (`checklists/phase-7-kubernetes.md` Mục 9; decisions D97, D100; `docs/testing/test-levels/e2e-testing.md` §3).
Run by hand against a deployed cluster, never from CI.

```
k8s/scripts/e2e.sh                       # all three engines + the transport probes
k8s/scripts/e2e.sh --project=chromium    # one engine
```

`e2e.sh` port-forwards the Istio gateway (HTTPS 8443, HTTP 8080) and runs `npx playwright test` here. Two things on
the machine are the owner's to do once, because they need a password; nothing in this repository changes them:

```
security add-trusted-cert -r trustRoot -k ~/Library/Keychains/login.keychain-db k8s/.local-ca/ca.crt
echo '127.0.0.1 unihub.local' | sudo tee -a /etc/hosts
```

There is no `ignoreHTTPSErrors`: a certificate the browser would reject fails the run (AC97.2).

| Spec | Row | What it does |
|---|---|---|
| `specs/student-journey.spec.ts` | M-69 | catalog → sign in → register free → QR e-ticket; register paid → checkout with a test token → `CONFIRMED` → QR e-ticket |
| `specs/organizer-journey.spec.ts` | M-70 | Organizer creates, publishes, edits and reschedules (API: the Web has no page for these) and, with the Admin, reads the report page in the browser |
| `specs/transport.spec.ts` | M-67 | TLS ≥ 1.2 with the local CA, TLS 1.1 refused, HTTP never reaches the API, `Content-Security-Policy` with `frame-ancestors` |

Every browser test also fails on a `securitypolicyviolation` event or a CSP error in the console
(`support/fixtures.ts`): that is the repeatable browser CSP check of `test-matrix.md` §4.5.

- **Accounts:** one Student per engine (`student1` Chromium, `student2` Firefox, `student3` WebKit), so per-user rate
  limits and "already registered" never collide. Passwords come from the cluster's Secret when needed and are never
  written anywhere.
- **Fixtures:** each test creates its own workshop with a unique title; `retries` is 0 on purpose (a retry hides a
  failure and spends rate-limit budget).
- **Reports:** `playwright-report/` and `test-results/` are not committed. A summary (tests, engines, duration, commit,
  image digests, overlay) goes into `docs/testing/reports/phase-7-cluster-checks.md`.
