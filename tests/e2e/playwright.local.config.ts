import { defineConfig, devices } from '@playwright/test'

// Browser checks that need no cluster: they run against a production build of apps/web served by `vite preview` on
// localhost (Phase 8 checklist 2.3, 2.4). The build is the real one, so it carries the production
// Content-Security-Policy <meta>; the only difference is VITE_SPIKE=true, which adds the /__spike page that the CSP
// spike (specs-local/spike.spec.ts) opens, and VITE_PAYMENT_TEST_MODE=true, which shows the checkout's test-token
// dropdown (specs-local/student-screens.spec.ts looks at the real payment form). It is built into node_modules, never into apps/web/dist.
//
//   npm run test:local        (from tests/e2e)
//
// The cluster suite (playwright.config.ts) stays the evidence for the deployed system; nothing here replaces it.
// Not Vite's default 4173: another dev server often holds it on this machine, and answers 404 to /login.
const port = Number(process.env.E2E_LOCAL_PORT ?? 4399)
// An address, not `localhost`: Playwright probes the URL it is given, and `localhost` can resolve to ::1 for one of
// the two sides and to 127.0.0.1 for the other.
const host = '127.0.0.1'
const web = '../../apps/web'
const outDir = 'node_modules/.spike-dist'

export default defineConfig({
  testDir: 'specs-local',
  retries: 0,
  workers: 2,
  fullyParallel: false,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  reporter: [['list'], ['json', { outputFile: 'test-results/local-results.json' }]],
  use: { baseURL: `http://${host}:${port}`, trace: 'retain-on-failure' },
  webServer: {
    command:
      `cd ${web} && VITE_SPIKE=true VITE_PAYMENT_TEST_MODE=true npx vite build --outDir ${outDir} --emptyOutDir` +
      ` && npx vite preview --outDir ${outDir} --host ${host} --port ${port} --strictPort`,
    url: `http://${host}:${port}/login`,
    reuseExistingServer: false,
    timeout: 180_000,
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'firefox', use: { ...devices['Desktop Firefox'] } },
    { name: 'webkit', use: { ...devices['Desktop Safari'] } },
  ],
})
