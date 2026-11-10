import { expect, test as base } from '@playwright/test'

import { settleCsp, watch, type PageHealth } from './page-health'

declare global {
  interface Window {
    __reportCsp?: (entry: string) => void
  }
}

type Options = {
  /**
   * For the one test that provokes a violation on purpose (a control that proves the guard can see one). Everything else is
   * guarded without being asked: a spec that forgets `health` still fails on a policy violation.
   */
  expectCspViolations: boolean
}

// The guard of the specs that need no cluster (specs-local/, Phase 8 task 8.4): `pageHealth` of support/fixtures.ts is the one
// of the cluster suite, and this is the same check for the specs that stub the API. It is `auto`, so no spec opts in and none
// can leave it out, and it collects through a binding of the page, so a report from a document the test has since left
// (a `goto`, a reload) is kept, which `window.__cspViolations` alone loses.
export const test = base.extend<{ health: PageHealth } & Options>({
  expectCspViolations: [false, { option: true }],
  health: [
    async ({ page, baseURL, expectCspViolations }, use, testInfo) => {
      const health = await watch(page)
      const reports: string[] = []
      await page.exposeFunction('__reportCsp', (entry: string) => {
        reports.push(entry)
      })
      await page.addInitScript(() => {
        // The same text `watch` builds, so a tolerated report is recognised by the same pattern.
        document.addEventListener('securitypolicyviolation', (event) => {
          window.__reportCsp?.(
            `${event.violatedDirective} blocked ${event.blockedURI || 'inline'} on ${document.location.pathname} at ${event.sourceFile}:${event.lineNumber}:${event.columnNumber} [${(event.sample || '').slice(0, 80)}]`,
          )
        })
      })
      await use(health)
      const selects = await page.evaluate(() => window.__selectsCreated ?? 0).catch(() => 0)
      const { failures, tolerated } = settleCsp({
        engine: testInfo.project.name,
        origin: new URL(baseURL ?? 'http://127.0.0.1').origin,
        reports: [...reports, ...health.cspViolations],
        consoleErrors: health.consoleErrors,
        selects,
      })
      if (tolerated.length > 0) {
        await testInfo.attach('tolerated-csp-webkit-select', { body: tolerated.join('\n'), contentType: 'text/plain' })
      }
      if (expectCspViolations) expect(failures.length, 'the control expects a Content-Security-Policy violation').toBeGreaterThan(0)
      else expect(failures, 'Content-Security-Policy violations').toEqual([])
    },
    { auto: true },
  ],
})

export { expect }
