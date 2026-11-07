import { defineConfig, devices } from '@playwright/test'

// The suite runs against a deployed Minikube cluster through the Istio gateway (Phase 7 checklist D100,
// e2e-testing.md §3), never from CI. k8s/scripts/e2e.sh starts the port-forwards and runs this.
//
// TLS is the real thing: the local CA (k8s/scripts/make-local-ca.sh) must be trusted by the machine
// (macOS login keychain) and `unihub.local` must resolve to 127.0.0.1; there is no `ignoreHTTPSErrors` anywhere,
// so a certificate the browser would reject fails the run (AC97.2).
export const baseURL = process.env.E2E_BASE_URL ?? 'https://unihub.local:8443'

// Node-side requests (the `request` fixture, the TLS probes) trust the same CA. Browsers use the system store.
process.env.NODE_EXTRA_CA_CERTS ??= new URL('../../k8s/.local-ca/ca.crt', import.meta.url).pathname

export default defineConfig({
  testDir: 'specs',
  globalSetup: './support/global-setup.ts',
  // No retries: a retry would hide a failure and also spend rate-limit budget (INV-17) that the next test needs.
  retries: 0,
  // Two workers, not one per engine: the owner's 16 GiB laptop swapped with three engines at four workers, the
  // cluster's probes timed out together and half the journeys failed (phase-7-cluster-checks.md, 10.1). Each engine
  // still has its own Student account (support/accounts.ts), whatever the worker count.
  workers: 2,
  fullyParallel: false,
  timeout: 120_000,
  expect: { timeout: 15_000 },
  reporter: [['list'], ['html', { open: 'never' }], ['json', { outputFile: 'test-results/results.json' }]],
  use: { baseURL, trace: 'retain-on-failure' },
  projects: [
    // Probes of the transport (TLS, HTTP, response headers): no browser, so once, not once per engine.
    { name: 'transport', testMatch: /transport\.spec\.ts/ },
    { name: 'chromium', testIgnore: /transport\.spec\.ts/, use: { ...devices['Desktop Chrome'] } },
    {
      name: 'firefox',
      testIgnore: /transport\.spec\.ts/,
      use: {
        ...devices['Desktop Firefox'],
        // Firefox keeps its own certificate store; this makes it read the system one, where the CA was trusted.
        launchOptions: { firefoxUserPrefs: { 'security.enterprise_roots.enabled': true } },
      },
    },
    { name: 'webkit', testIgnore: /transport\.spec\.ts/, use: { ...devices['Desktop Safari'] } },
  ],
})
