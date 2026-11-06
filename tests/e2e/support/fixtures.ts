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
      window.__cspViolations?.push(`${event.violatedDirective} blocked ${event.blockedURI || 'inline'}`)
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
    await use(await new OrganizerApi(request).signIn(organizer()))
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
      await testInfo.attach('console-errors', { body: health.consoleErrors.join('\n') || '(none)', contentType: 'text/plain' })
      expect([...health.cspViolations, ...cspInConsole], 'Content-Security-Policy violations').toEqual([])
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
