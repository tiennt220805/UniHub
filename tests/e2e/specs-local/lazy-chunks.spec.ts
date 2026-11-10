import { expect, test as base, type Page } from '@playwright/test'

import { watch, type PageHealth } from '../support/page-health'

// Phase 8, task 7.5: the administration pages are lazy chunks, so a chunk can fail to arrive after the page has loaded (a rolling
// deploy behind the gateway whose next pod no longer has that hash, or a dropped connection). The page area must say so, in
// Vietnamese, with a way out, inside the admin frame, and not show React Router's default English error page with a module URL.

const test = base.extend<{ health: PageHealth }>({
  health: async ({ page }, use) => {
    await use(await watch(page))
  },
})

async function asManager(page: Page) {
  await page.addInitScript(() =>
    sessionStorage.setItem(
      'unihub.session',
      JSON.stringify({
        accessToken: 'opaque',
        expiresAt: Date.now() + 3_600_000,
        user: { id: '2b1c3d4e-5f60-4a71-8b92-0c1d2e3f4a5b', fullName: 'Trần Minh Anh', email: 'anh@unihub.test', roles: ['Organizer'] },
        permissions: ['WORKSHOP_CREATE', 'WORKSHOP_EDIT', 'REPORT_VIEW'],
      }),
    ),
  )
}

test.use({ viewport: { width: 1280, height: 800 } })

test('a lazy page that does not arrive shows a Vietnamese error inside the admin frame, with a reload', async ({ page }) => {
  await asManager(page)
  await page.route('**/assets/WorkshopListPage-*.js', (route) => route.abort())
  await page.goto('/admin/workshops')

  const alert = page.getByRole('alert')
  await expect(alert).toContainText('Không tải được trang này.')
  await expect(alert.getByRole('button', { name: 'Tải lại' })).toBeVisible()
  // The frame is still there: a sidebar with the way back to the student pages.
  await expect(page.getByRole('link', { name: 'Về trang sinh viên' })).toBeVisible()
  // Nothing of React Router's default page, and no module URL for a person to read.
  await expect(page.getByText('Unexpected Application Error')).toHaveCount(0)
  await expect(page.getByText(/dynamically imported module|\.js/)).toHaveCount(0)
})

test('"Tải lại" asks for the page again, and once the chunk is there the page works', async ({ page, browserName }) => {
  // Playwright's WebKit does not ask again for a module URL it has aborted, even after a reload (observed 2026-11-10: the document
  // reloads, the chunk is not requested, the error stays). Whether Safari itself does that was not established, so the recovery is
  // shown on Chromium and Firefox only; on all three engines the test above shows the error. After a real deploy the hash in the
  // URL changes, so a reload asks for a new URL anyway.
  test.skip(browserName === 'webkit', 'an aborted module URL is not requested again by Playwright WebKit')
  await asManager(page)
  let blocked = true
  await page.route('**/assets/WorkshopListPage-*.js', (route) => (blocked ? route.abort() : route.continue()))
  await page.route('**/api/v1/admin/workshops**', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: [], page: { page: 0, size: 20, totalElements: 0, totalPages: 0 } }) }),
  )
  await page.goto('/admin/workshops')
  await expect(page.getByRole('alert')).toContainText('Không tải được trang này.')
  blocked = false
  await page.getByRole('button', { name: 'Tải lại' }).click()
  await expect(page.getByRole('heading', { name: 'Workshop', level: 1, exact: true })).toBeVisible()
})

test('control: with nothing blocked the same route renders its page, so the check above can fail', async ({ page }) => {
  await asManager(page)
  await page.route('**/api/v1/admin/workshops**', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: [], page: { page: 0, size: 20, totalElements: 0, totalPages: 0 } }) }),
  )
  await page.goto('/admin/workshops')
  await expect(page.getByRole('heading', { name: 'Workshop', level: 1, exact: true })).toBeVisible()
  await expect(page.getByText('Không tải được trang này.')).toHaveCount(0)
})
