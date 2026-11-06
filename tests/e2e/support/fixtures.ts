import { expect, test as base, type Page } from '@playwright/test'

import { organizer, studentFor, type Account } from './accounts'
import { OrganizerApi, StudentApi } from './api'

// What a page reported about itself while a test ran. A Content-Security-Policy violation is a failure of the
// test that caused it (AC85.2, test-matrix.md §4.5: the repeatable browser CSP check), however green the rest is.
type PageHealth = { cspViolations: string[]; consoleErrors: string[] }

declare global {
  interface Window {
    __cspViolations?: string[]
  }
}

async function watch(page: Page): Promise<PageHealth> {
  const health: PageHealth = { cspViolations: [], consoleErrors: [] }
  // The event fires in the page; the init script runs before the page's own scripts, in every frame.
  await page.addInitScript(() => {
    window.__cspViolations = []
    document.addEventListener('securitypolicyviolation', (event) => {
      window.__cspViolations?.push(
        `${event.violatedDirective} blocked ${event.blockedURI || 'inline'} on ${document.location.pathname} at ${event.sourceFile}:${event.lineNumber}:${event.columnNumber} [${(event.sample || '').slice(0, 80)}]`,
      )
    })
  })
  page.on('console', (message) => {
    if (message.type() === 'error') health.consoleErrors.push(message.text())
  })
  return health
}

type Fixtures = {
  /** The Student of this engine (support/accounts.ts). */
  student: Account
  organizerApi: OrganizerApi
  studentApi: StudentApi
  /** Collects CSP violations and console errors of `page`; asserts none of the former at teardown. */
  pageHealth: PageHealth
}

export const test = base.extend<Fixtures>({
  student: async ({}, use, testInfo) => {
    await use(studentFor(testInfo.project.name))
  },
  organizerApi: async ({ request }, use) => {
    const api = await new OrganizerApi(request).signIn(organizer())
    await use(api)
    // The Web's catalog shows only the first page of 20 (WorkshopCatalogPage: useWorkshops({})), so a run that left its
    // workshops behind would push the next run's out of sight. Each test takes its own workshops out of the catalog.
    await api.cancelAllCreated()
  },
  studentApi: async ({ request, student }, use) => {
    await use(await new StudentApi(request).signIn(student))
  },
  pageHealth: [
    async ({ page }, use, testInfo) => {
      const health = await watch(page)
      await use(health)
      const fromPage = await page.evaluate(() => window.__cspViolations ?? []).catch(() => [] as string[])
      health.cspViolations.push(...fromPage)
      // Console errors other than CSP ones are attached, not failed on: a 4xx the journey provokes on purpose
      // logs "Failed to load resource". A CSP error text in the console counts as a violation too.
      const cspInConsole = health.consoleErrors.filter((text) => /content security policy|violates the following/i.test(text))
      // The one tolerated exception, decided by the owner 2026-11-06 (phase-7-cluster-checks.md, 9.4): WebKit reports a
      // `style-src-attr` violation with an empty sample whenever a page creates a native <select>, with the strict CSP
      // and no application code involved (reproduced on a page of its own). It is the checkout's test-token dropdown,
      // shown only in a test-mode build. Anything else on any engine, or this on any other page, still fails.
      const isWebkitSelectQuirk = (entry: string) =>
        testInfo.project.name === 'webkit' && /^style-src-attr blocked inline on \/me\/checkout\/[^ ]+ at \S+:\d+:\d+ \[\]$/.test(entry)
      const tolerated = health.cspViolations.filter(isWebkitSelectQuirk)
      const violations = health.cspViolations.filter((entry) => !isWebkitSelectQuirk(entry))
      // Each tolerated event is followed by the same message in the console; take out that many, not all of them.
      const consoleLeft = [...cspInConsole]
      for (let i = 0; i < tolerated.length; i++) {
        const at = consoleLeft.findIndex((text) => /^Refused to apply a stylesheet because its hash/.test(text))
        if (at >= 0) consoleLeft.splice(at, 1)
      }
      await testInfo.attach('console-errors', { body: health.consoleErrors.join('\n') || '(none)', contentType: 'text/plain' })
      if (tolerated.length > 0) {
        await testInfo.attach('tolerated-csp-webkit-select', { body: tolerated.join('\n'), contentType: 'text/plain' })
      }
      expect([...violations, ...consoleLeft], 'Content-Security-Policy violations').toEqual([])
    },
    { auto: true },
  ],
})

export { expect }

/** A title no other run has used, so a workshop of this run is the only one that matches it. */
export function uniqueTitle(label: string): string {
  return `E2E ${label} ${Date.now().toString(36)}${Math.floor(Math.random() * 1296).toString(36)}`
}

export async function signInThroughTheForm(page: Page, account: Account): Promise<void> {
  await page.getByLabel('Email').fill(account.email)
  await page.getByLabel('Mật khẩu').fill(account.password)
  await page.getByRole('button', { name: 'Đăng nhập' }).click()
  // The session lives in sessionStorage and is written when the request returns: navigating away before that
  // would abandon the sign-in, so wait until the header shows it.
  await expect(page.getByRole('button', { name: 'Đăng xuất' })).toBeVisible()
}
