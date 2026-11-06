import type { Page } from '@playwright/test'

import { admin, organizer } from '../support/accounts'
import { expect, signInThroughTheForm, test, uniqueTitle } from '../support/fixtures'

// M-70 (Phase 7 task 9.3), the part the Web has: the Organizer and the Admin read the report page of a workshop.
// Create, publish, edit and reschedule have no page in the Web (it builds only the report), so they are API calls
// through the same gateway and over the same HTTPS; the run records M-70 as a partial journey (phase-7.md §5).
test('an Organizer creates, publishes, edits and reschedules a workshop; Organizer and Admin read its report', async ({
  page,
  browser,
  organizerApi,
  studentApi,
}) => {
  const title = uniqueTitle('organizer')
  const draft = await organizerApi.create({ title, paid: false, capacity: 5 })
  expect(draft.status).toBe('DRAFT')

  // A DRAFT is invisible to the public: the same call without a token is a 404.
  const anonymous = await page.request.get(`/api/v1/workshops/${draft.id}`)
  expect(anonymous.status()).toBe(404)

  await organizerApi.publish(draft.id)
  const edited = uniqueTitle('organizer edited')
  const newStart = new Date(Date.now() + 5 * 86_400_000)
  const newEnd = new Date(newStart.getTime() + 3 * 3_600_000)
  await organizerApi.update(draft.id, { title: edited, location: 'Room E2E 2' })
  const rescheduled = await organizerApi.update(draft.id, {
    startTime: newStart.toISOString(),
    endTime: newEnd.toISOString(),
  })
  expect(new Date(rescheduled.startTime).getTime()).toBe(newStart.getTime())

  // One Student holds a seat, so the report has something to count.
  await studentApi.register(draft.id)

  // The Organizer, in the browser: the detail page shows the edit and the new time, and links to the report.
  await page.goto('/login')
  const account = organizer()
  await signInThroughTheForm(page, account)
  await page.goto(`/workshops/${draft.id}`)
  await expect(page.getByRole('heading', { level: 1, name: edited })).toBeVisible()
  await expect(page.getByText('Room E2E 2')).toBeVisible()
  const startTime = await page.locator('time').first().getAttribute('datetime')
  expect(new Date(startTime ?? '').getTime()).toBe(newStart.getTime())

  await page.getByRole('link', { name: 'Xem báo cáo' }).click()
  await expect(page).toHaveURL(new RegExp(`/admin/workshops/${draft.id}/report$`))
  await expectReport(page, { capacity: 5, taken: 1, confirmed: 1 })

  // The Admin, in a browser context of their own, reads the same numbers.
  const context = await browser.newContext()
  try {
    const adminPage = await context.newPage()
    await adminPage.goto('/login')
    await signInThroughTheForm(adminPage, admin())
    await adminPage.goto(`/admin/workshops/${draft.id}/report`)
    await expectReport(adminPage, { capacity: 5, taken: 1, confirmed: 1 })
  } finally {
    await context.close()
  }
})

// The report is read at the replica and refreshed on a timer, so a count may take a few seconds to show.
async function expectReport(page: Page, expected: { capacity: number; taken: number; confirmed: number }) {
  const report = page.getByRole('region', { name: 'Báo cáo workshop' })
  await expect(report).toBeVisible()
  const figure = (label: string) => report.locator('dt', { hasText: label }).locator('xpath=following-sibling::dd[1]')
  await expect(figure('Sức chứa')).toHaveText(String(expected.capacity))
  await expect(figure('Chỗ đã giữ')).toHaveText(String(expected.taken), { timeout: 30_000 })
  await expect(figure('Đăng ký đã xác nhận')).toHaveText(String(expected.confirmed), { timeout: 30_000 })
}
