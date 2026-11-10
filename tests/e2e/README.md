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
| `specs/student-journey.spec.ts` | M-69 | catalog (found by searching its title) → sign in → register free → QR e-ticket; register paid → checkout with a test token → `CONFIRMED` → QR e-ticket |
| `specs/catalog.spec.ts` | M-82 | the catalog narrowed by topic, speaker, place and date range (state in the URL, Back, "Xóa bộ lọc", a backwards range), and paging over 21 workshops (one engine: it seeds 21 workshops, 63 Organizer calls) |
| `specs/organizer-journey.spec.ts` | M-70 | the whole Organizer journey **in the browser**: create form → cover and brochure upload (to the object store on the page's own origin) → publish → edit → reschedule (each with its confirmation) → the public copy (title, time, a cover read without a token as an image with `nosniff`, drawn on the detail page) → the report; the Admin reads the same numbers. The only API calls are the Student's registration and the anonymous reads |
| `specs/theme.spec.ts` | M-86 | the header toggle (dark, light, system), kept across a reload, and the same choice in the student pages and in the administration frame |
| `specs/transport.spec.ts` | M-67 | TLS ≥ 1.2 with the local CA, TLS 1.1 refused, HTTP never reaches the API, `Content-Security-Policy` with `frame-ancestors` |

Every browser test also fails on a `securitypolicyviolation` event or a CSP error in the console
(`support/fixtures.ts`): that is the repeatable browser CSP check of `test-matrix.md` §4.5. The specs of `specs-local/` have the same
guard from `support/local-fixtures.ts`, automatic and collected through a binding of the page (so a report from a document the test
has since left is kept); a test that provokes a violation on purpose says so with `test.use({ expectCspViolations: true })` and then
fails if the guard did **not** see one (`spike.spec.ts` is that control, on each engine). Both share `settleCsp` in
`support/page-health.ts`, which holds the one tolerated report (WebKit, the checkout `<select>`, the owner's decision of 2026-11-06). The local guard
also sets aside one report that is Playwright's, not the page's: WebKit reports the `<style>` Playwright injects for **any** screenshot
(`style-src-elem blocked inline … at :N:M []`); it is dropped only while a screenshot is being taken and attached to the test as `csp-reports-from-screenshots`.

- **Accounts:** one Student per engine (`student1` Chromium, `student2` Firefox, `student3` WebKit), so per-user rate
  limits and "already registered" never collide. Passwords come from the cluster's Secret when needed and are never
  written anywhere.
- **Fixtures:** each test creates its own workshop with a unique title; `retries` is 0 on purpose (a retry hides a
  failure and spends rate-limit budget).
- **Reports:** `playwright-report/` and `test-results/` are not committed. A summary (tests, engines, duration, commit,
  image digests, overlay) goes into `docs/testing/reports/phase-7-cluster-checks.md`.

## Notes from the first runs on three engines

- **Before a run** `support/global-setup.ts` cancels every workshop titled `E2E …` that an earlier run left behind and refuses to
  start when more than 16 other workshops are published. The Web's catalog page shows only the first 20 (no search, no paging),
  so a fuller catalog hides the workshops the journeys create. Each test also cancels its own workshops.
- **WebKit and `<select>`:** with the strict CSP, WebKit reports a `style-src-attr` violation for any native `<select>` (reproduced
  on a page with nothing else). The guard in `support/fixtures.ts` tolerates at most two such reports per `<select>` in the DOM, on `/me/checkout/…`, from
  the application's own bundle, on the `webkit` project (decided by the owner, 2026-11-06); the test attaches `tolerated-csp-webkit-select` whenever it does. Anything
  else, on any engine, fails the test.
- **429:** the Organizer helper waits out a `429` as `Retry-After` says (the rate limit is per identity).

## The cluster specs against the local stack (no cluster)

`specs/` can run without Minikube, to see that the journeys work on a real backend before a cluster run: `bootRun` on `:8080` with the dev seeder, MinIO and the other containers from `docker-compose.yml`, and a production build served by `vite preview` on **`localhost:5173`** (that port because the backend signs upload URLs for `UNIHUB_OBJECT_STORAGE_PUBLIC_ENDPOINT`, which in a dev run is `http://localhost:5173`, and a signature covers the host). Build with `VITE_PAYMENT_TEST_MODE=true` so checkout offers the test tokens.

```
E2E_BASE_URL=http://localhost:5173 \
UNIHUB_DEV_SEED_STUDENT_PASSWORD=… UNIHUB_DEV_SEED_ORGANIZER_PASSWORD=… UNIHUB_DEV_SEED_ADMIN_PASSWORD=… \
npx playwright test --project=chromium      # then firefox, then webkit
```

The passwords are the ones the backend was started with (`support/accounts.ts` reads the variable of the same name from the environment before it asks `kubectl`; there is no default). `E2E_STUDENT_EMAIL` makes every engine use one Student when the others' passwords are unknown; then run the engines one at a time, a few seconds apart, because one identity shares one rate-limit budget (INV-17) and a back-to-back run can be answered `429`. Not run this way: `--project=transport` (TLS probes). A green run here is not the cluster run (checklist 9.3).

## Local browser checks (no cluster): `npm run test:local`

`playwright.local.config.ts` + `specs-local/` run on the three engines against a **production build of `apps/web`** served by
`vite preview` on `127.0.0.1:4399` (override with `E2E_LOCAL_PORT`; Vite's default 4173 is often taken). The build carries the real
Content-Security-Policy `<meta>`; the one difference is `VITE_SPIKE=true`, which adds the `/__spike` page. The build goes into
`apps/web/node_modules/.spike-dist`, never into `apps/web/dist`. These specs are Phase 8 evidence (the UI-library CSP spike, task 2.3, and
the light/dark theme, task 2.4, and the two frames at 1280, 900 and 360 px, task 2.7; the G2–G4 screens; and Phase 8 Mục 7: `motion.spec.ts` reduced motion, `worst-case.spec.ts` worst-case data at 360/768/1280 px, `mobile-native.spec.ts` touch rules, `a11y.spec.ts` axe-core in both themes plus keyboard and live regions — `@axe-core/playwright` is a devDependency here, a test tool only); they do not replace the cluster suite above, and `support/page-health.ts` is the same CSP watcher both use.

**With a real backend running on `localhost:8080`** (a developer's `bootRun`), run it as `UNIHUB_API_PROXY_TARGET=http://127.0.0.1:1 npm run test:local`: `vite preview` inherits the dev proxy, so the specs that use a fake session token (`layouts.spec.ts`, `theme.spec.ts`) would otherwise be answered `401` by the real API and end their session. The specs that stub the API with `page.route` (`student-screens`, `admin-screens`) are not affected.
