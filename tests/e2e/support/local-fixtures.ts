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
      // Playwright's own screenshot injects a <style> into the page, and WebKit reports that as a violation of the page's policy
      // ("style-src-elem blocked inline", no source file, empty sample): the tool's doing, not the application's, and it happens
      // for any screenshot of any page under a strict policy (checked on /login, nothing else done). It is set aside only when it
      // arrives while a screenshot is being taken and in that exact form; it is attached to the test, and not a tolerance for
      // anything the page does. Any other report, at any time, still fails.
      const fromScreenshot: string[] = []
      let screenshotUntil = 0
      const isScreenshotStyle = (entry: string) => /^style-src-elem blocked inline on \S+ at :\d+:\d+ \[\]$/.test(entry)
      await page.exposeFunction('__reportCsp', (entry: string) => {
        if (Date.now() < screenshotUntil && isScreenshotStyle(entry)) fromScreenshot.push(entry)
        else reports.push(entry)
      })
      const takeScreenshot = page.screenshot.bind(page)
      page.screenshot = async (options) => {
        screenshotUntil = Date.now() + 60_000
        try {
          return await takeScreenshot(options)
        } finally {
          // The report is delivered a moment after the call returns.
          screenshotUntil = Date.now() + 500
        }
      }
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
      // The console repeats each report in words; take out as many as were set aside, not all of them.
      const consoleErrors = [...health.consoleErrors]
      for (let i = 0; i < fromScreenshot.length; i++) {
        const at = consoleErrors.findIndex((text) => /^Refused to apply a stylesheet because its hash/.test(text))
        if (at >= 0) consoleErrors.splice(at, 1)
      }
      const { failures, tolerated } = settleCsp({
        engine: testInfo.project.name,
        origin: new URL(baseURL ?? 'http://127.0.0.1').origin,
        reports: [...reports, ...health.cspViolations],
        consoleErrors,
        selects,
      })
      if (fromScreenshot.length > 0) {
        await testInfo.attach('csp-reports-from-screenshots', { body: fromScreenshot.join('\n'), contentType: 'text/plain' })
      }
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
