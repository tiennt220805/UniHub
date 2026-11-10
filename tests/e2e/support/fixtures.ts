import { expect, test as base, type Page } from '@playwright/test'

import { organizer, studentFor, type Account } from './accounts'
import { OrganizerApi, StudentApi } from './api'
import { settleCsp, watch, type PageHealth } from './page-health'

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
    // Each test takes its own workshops out of the catalog: published test workshops left behind would show to the next
    // reader of the catalog (the owner's demo, the next run's counts of what a search finds).
    await api.cancelAllCreated()
  },
  studentApi: async ({ request, student }, use) => {
    await use(await new StudentApi(request).signIn(student))
  },
  pageHealth: [
    async ({ page }, use, testInfo) => {
      const health = await watch(page)
      await use(health)
      const seen = await page
        .evaluate(() => ({ violations: window.__cspViolations ?? [], selects: window.__selectsCreated ?? 0 }))
        .catch(() => ({ violations: [] as string[], selects: 0 }))
      health.cspViolations.push(...seen.violations)
      const { failures, tolerated } = settleCsp({
        engine: testInfo.project.name,
        origin: originUnderTest,
        reports: health.cspViolations,
        consoleErrors: health.consoleErrors,
        selects: seen.selects,
      })
      await testInfo.attach('console-errors', { body: health.consoleErrors.join('\n') || '(none)', contentType: 'text/plain' })
      if (tolerated.length > 0) {
        await testInfo.attach('tolerated-csp-webkit-select', { body: tolerated.join('\n'), contentType: 'text/plain' })
      }
      expect(failures, 'Content-Security-Policy violations').toEqual([])
    },
    { auto: true },
  ],
})

export { expect }

const originUnderTest = new URL(process.env.E2E_BASE_URL ?? 'https://unihub.local:8443').origin

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
