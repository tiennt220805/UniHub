import type { Page } from '@playwright/test'

import { admin, organizer } from '../support/accounts'
import { expect, signInThroughTheForm, test, uniqueTitle } from '../support/fixtures'

// M-70 (Phase 8 task 8.3): the whole Organizer journey in the browser. Create (the form), the cover and the brochure
// (uploads that go to the object store on the page's own origin), publish, edit, reschedule (each with its confirmation),
// then the report; and what the public sees of the result. Nothing here is an API call for the Organizer: the only API calls
// are the Student's registration, so the report has a seat to count, and the anonymous reads that prove what the public gets.
//
// The administration screens are one reading of REQ §1.3.3/§1.3.4; the stubbed-API specs of `specs-local/` check how they
// look and behave under the CSP, and this one that they work against the real backend, the object store and the gateway.

// A 1×1 PNG and the smallest PDF the post-upload check takes: real files of the types the upload accepts.
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64')
const PDF = Buffer.from(
  '%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n' +
    '3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]>>endobj\ntrailer<</Root 1 0 R/Size 4>>\n%%EOF\n',
)

/** A Vietnam wall-clock time as the `datetime-local` field wants it, and the instant it means. */
function vietnamTime(daysAhead: number, hour: number) {
  const day = new Date(Date.now() + daysAhead * 86_400_000).toLocaleDateString('en-CA', { timeZone: 'Asia/Ho_Chi_Minh' })
  const clock = `${String(hour).padStart(2, '0')}:00`
  return { field: `${day}T${clock}`, instant: new Date(`${day}T${clock}:00+07:00`) }
}

test('an Organizer creates a workshop, gives it a cover and a brochure, publishes, edits and reschedules it, and reads its report', async ({
  page,
  browser,
  organizerApi,
  studentApi,
}) => {
  test.setTimeout(180_000)
  const title = uniqueTitle('organizer')
  const edited = uniqueTitle('organizer edited')
  const start = vietnamTime(5, 9)
  const end = vietnamTime(5, 12)
  const newStart = vietnamTime(6, 14)
  const newEnd = vietnamTime(6, 16)

  // Sign in, and from the list start a new workshop.
  await page.goto('/login')
  await signInThroughTheForm(page, organizer())
  await page.goto('/admin/workshops')
  await page.getByRole('link', { name: 'Tạo workshop' }).first().click()
  await expect(page.getByRole('heading', { level: 1, name: 'Tạo workshop' })).toBeVisible()

  // Create: the draft is the workshop's own page, with the next steps.
  await page.getByLabel(/^Tên workshop/).fill(title)
  await page.getByLabel(/^Diễn giả/).fill('E2E Speaker')
  await page.getByLabel(/^Địa điểm/).fill('Room E2E')
  await page.getByLabel(/^Bắt đầu/).fill(start.field)
  await page.getByLabel(/^Kết thúc/).fill(end.field)
  await page.getByLabel(/^Sức chứa/).fill('5')
  await page.getByRole('button', { name: 'Tạo bản nháp' }).click()
  await expect(page).toHaveURL(/\/admin\/workshops\/[0-9a-f-]{36}$/)
  const id = /\/admin\/workshops\/([0-9a-f-]{36})$/.exec(page.url())![1]
  organizerApi.track(id)
  await expect(page.getByRole('status').filter({ hasText: 'Đã tạo bản nháp.' })).toBeVisible()
  await expect(page.getByRole('heading', { level: 1, name: title })).toBeVisible()

  // A draft is invisible to the public: the same read without a token is a 404.
  expect((await page.request.get(`/api/v1/workshops/${id}`)).status()).toBe(404)

  // The cover: chosen, previewed, put to the object store on this origin, confirmed.
  const cover = page.getByRole('region', { name: 'Ảnh bìa' })
  await cover.getByLabel('Chọn tệp ảnh bìa').setInputFiles({ name: 'hoi-truong.png', mimeType: 'image/png', buffer: PNG })
  await expect(cover.getByRole('img', { name: 'Xem trước ảnh đã chọn' })).toBeVisible()
  await cover.getByRole('button', { name: 'Tải lên' }).click()
  await expect(cover.getByText('Đã cập nhật ảnh bìa.')).toBeVisible()

  // The brochure: the same path for a PDF; the summary is made later, and the page does not claim it.
  const brochure = page.getByRole('region', { name: 'Brochure' })
  await brochure.getByLabel('Chọn tệp brochure PDF').setInputFiles({ name: 'gioi-thieu.pdf', mimeType: 'application/pdf', buffer: PDF })
  await brochure.getByRole('button', { name: 'Tải lên' }).click()
  await expect(brochure.getByText('Đã nhận brochure.')).toBeVisible()

  // Publish, after a confirmation that says what it does.
  await page.getByRole('region', { name: 'Trạng thái' }).getByRole('button', { name: 'Công bố' }).click()
  const publishing = page.getByRole('alertdialog', { name: 'Công bố workshop?' })
  await publishing.getByRole('button', { name: 'Công bố' }).click()
  await expect(publishing).toBeHidden()
  await expect(page.getByRole('region', { name: 'Trạng thái' }).getByRole('button', { name: 'Công bố' })).toHaveCount(0)
  expect((await page.request.get(`/api/v1/workshops/${id}`)).status()).toBe(200)

  // One Student holds a seat, so the reschedule has someone to tell and the report something to count.
  await studentApi.register(id)

  // Edit: a new title and place, saved without a confirmation (the time did not change).
  await page.getByLabel(/^Tên workshop/).fill(edited)
  await page.getByLabel(/^Địa điểm/).fill('Room E2E 2')
  await page.getByRole('button', { name: 'Lưu thay đổi' }).click()
  await expect(page.getByRole('heading', { level: 1, name: edited })).toBeVisible()

  // Reschedule: a published workshop asks first, shows the old and the new time, and says the students are told.
  await page.getByLabel(/^Bắt đầu/).fill(newStart.field)
  await page.getByLabel(/^Kết thúc/).fill(newEnd.field)
  await page.getByRole('button', { name: 'Lưu thay đổi' }).click()
  const rescheduling = page.getByRole('alertdialog', { name: 'Đổi lịch workshop?' })
  await expect(rescheduling.getByText('Giờ mới', { exact: true })).toBeVisible()
  await rescheduling.getByRole('button', { name: 'Đổi lịch và gửi thông báo' }).click()
  await expect(rescheduling).toBeHidden()
  await expect(page.getByLabel(/^Bắt đầu/)).toHaveValue(newStart.field)

  // What the public gets, read without a token: the edit, the new time, and a cover that is an image on this origin.
  const publicCopy = await page.request.get(`/api/v1/workshops/${id}`)
  const detail = (await publicCopy.json()) as { title: string; location: string; startTime: string; coverImageUrl: string | null }
  expect(detail.title).toBe(edited)
  expect(detail.location).toBe('Room E2E 2')
  expect(new Date(detail.startTime).getTime()).toBe(newStart.instant.getTime())
  expect(detail.coverImageUrl).toMatch(/^\/media\/covers\//)
  const coverRead = await page.request.get(detail.coverImageUrl!)
  expect(coverRead.status()).toBe(200)
  expect(coverRead.headers()['content-type']).toBe('image/png')
  expect(coverRead.headers()['x-content-type-options']).toBe('nosniff')

  // The detail page shows it too: the title, the place and the picture, drawn.
  await page.goto(`/workshops/${id}`)
  await expect(page.getByRole('heading', { level: 1, name: edited })).toBeVisible()
  await expect(page.getByText('Room E2E 2')).toBeVisible()
  const startTime = await page.locator('time').first().getAttribute('datetime')
  expect(new Date(startTime ?? '').getTime()).toBe(newStart.instant.getTime())
  await expect
    .poll(() => page.locator('img[src^="/media/covers/"]').first().evaluate((img: HTMLImageElement) => img.complete && img.naturalWidth > 0))
    .toBe(true)

  // The report, from the link on the Organizer's own page for the workshop.
  await page.goto(`/admin/workshops/${id}`)
  // Read again from the server: the brochure the page was told about is the one the workshop now has.
  await expect(page.getByRole('region', { name: 'Brochure' }).getByRole('button', { name: 'Thay brochure' })).toBeVisible()
  await page.getByRole('link', { name: 'Xem báo cáo' }).click()
  await expect(page).toHaveURL(new RegExp(`/admin/workshops/${id}/report$`))
  await expectReport(page, { capacity: 5, taken: 1, confirmed: 1 })

  // The Admin, in a browser context of their own, reads the same numbers.
  const context = await browser.newContext()
  try {
    const adminPage = await context.newPage()
    await adminPage.goto('/login')
    await signInThroughTheForm(adminPage, admin())
    await adminPage.goto(`/admin/workshops/${id}/report`)
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
