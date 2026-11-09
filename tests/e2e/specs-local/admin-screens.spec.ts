import { expect, test as base, type Page, type Request } from '@playwright/test'
import { mkdirSync } from 'node:fs'

import { watch, type PageHealth } from '../support/page-health'

// Phase 8, G4 (checklist 6.2–6.8, 8.4): the administration screens in a real browser under the production Content-Security-Policy,
// with the API answered by `page.route`. What Vitest cannot see is checked here: Radix menus and alert dialogs lock the page
// scroll without a <style> (D131), a `data:` preview of a chosen image is allowed by `img-src`, an XMLHttpRequest PUT to the
// application's own origin passes `connect-src 'self'` and carries only the signed Content-Type, the ring of the report is
// drawn by presentation attributes, and a `datetime-local` value is read as Vietnam time in a browser that is somewhere else.
// The PUT here is answered by this file; the PUT through the Istio gateway to MinIO is task 9.2.

const test = base.extend<{ health: PageHealth }>({
  health: async ({ page }, use) => {
    await use(await watch(page))
  },
})

const SHOTS = 'test-results/g4-screens'
mkdirSync(SHOTS, { recursive: true })

const WORKSHOP_ID = '3f2b8c1e-6d4a-4a57-9b0e-1c2d3e4f5a6b'
const DRAFT_ID = '9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d'

const summary = (overrides: Record<string, unknown> = {}) => ({
  id: WORKSHOP_ID,
  title: 'Nhập môn Machine Learning cho sinh viên năm hai',
  location: 'Hội trường A2',
  startTime: '2026-11-14T06:30:00Z',
  endTime: '2026-11-14T09:30:00Z',
  status: 'PUBLISHED',
  speakerName: 'TS. Lê Minh Quân',
  capacity: 60,
  seatsTaken: 42,
  isPaid: true,
  price: 150000,
  coverImageUrl: null,
  ...overrides,
})

const detail = (overrides: Record<string, unknown> = {}) => ({
  ...summary(),
  description: 'Từ hồi quy tuyến tính đến mạng nơ-ron đầu tiên.',
  aiSummary: null,
  speakerBio: null,
  hasBrochure: false,
  version: 3,
  ...overrides,
})

const pageOf = (items: unknown[]) => ({ items, page: { page: 0, size: 20, totalElements: items.length, totalPages: 1 } })

type Reply = { status?: number; body?: unknown }
type Handler = Reply | ((request: Request) => Reply | Promise<Reply>)

/** Answers `METHOD /path` exactly; anything else is a 404 problem, so a request the page was not meant to make shows up. */
async function api(page: Page, handlers: Record<string, Handler>) {
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const handler = handlers[`${request.method()} ${new URL(request.url()).pathname}`]
    if (handler === undefined) {
      return route.fulfill({ status: 404, contentType: 'application/problem+json', body: JSON.stringify({ type: 'about:blank', title: 'Not found', status: 404 }) })
    }
    const reply = typeof handler === 'function' ? await handler(request) : handler
    const status = reply.status ?? 200
    return route.fulfill({
      status,
      contentType: status >= 400 ? 'application/problem+json' : 'application/json',
      body: JSON.stringify(reply.body ?? {}),
    })
  })
}

async function signedIn(page: Page, permissions: string[]) {
  await page.addInitScript((granted) => {
    sessionStorage.setItem(
      'unihub.session',
      JSON.stringify({
        accessToken: 'opaque',
        expiresAt: Date.now() + 3_600_000,
        user: { id: '2b1c3d4e-5f60-4a71-8b92-0c1d2e3f4a5b', fullName: 'Trần Minh Anh', email: 'anh@unihub.test', roles: ['Organizer'] },
        permissions: granted,
      }),
    )
  }, permissions)
}

const MANAGER = ['WORKSHOP_CREATE', 'WORKSHOP_EDIT', 'REPORT_VIEW']

const noHorizontalScroll = (page: Page) => page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)
const shot = (page: Page, name: string, testInfo: { project: { name: string } }) =>
  page.screenshot({ path: `${SHOTS}/${testInfo.project.name}-${name}.png`, fullPage: true })

// A 1×1 PNG: a real image, so the browser can draw the `data:` preview.
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64')

test.describe('list (S10)', () => {
  test('1280 px: the table, the sidebar, and the row menu with its confirmations open without a policy violation', async ({ page, health }, testInfo) => {
    await page.setViewportSize({ width: 1280, height: 900 })
    await signedIn(page, MANAGER)
    await api(page, {
      'GET /api/v1/admin/workshops': {
        body: pageOf([summary({ id: DRAFT_ID, title: 'Bản nháp A', status: 'DRAFT', seatsTaken: 0 }), summary(), summary({ id: 'c1', title: 'Đã hủy C', status: 'CANCELLED' })]),
      },
    })
    await page.goto('/admin/workshops')

    const table = page.getByRole('table', { name: 'Danh sách workshop' })
    await expect(table).toBeVisible()
    await expect(page.getByRole('navigation', { name: 'Quản trị' }).getByRole('link', { name: 'Danh sách workshop' })).toBeVisible()
    await expect(table.getByRole('row')).toHaveCount(4)
    expect(await noHorizontalScroll(page)).toBe(true)

    // The menu and the two confirmations lock the page scroll through the CSP-safe stand-in; any `<style>` would be reported.
    await page.getByRole('button', { name: 'Thao tác với Bản nháp A' }).click()
    await expect(page.getByRole('menuitem', { name: 'Công bố' })).toBeVisible()
    await page.getByRole('menuitem', { name: 'Công bố' }).click()
    const publish = page.getByRole('alertdialog', { name: 'Công bố workshop?' })
    await expect(publish).toBeVisible()
    expect(await page.evaluate(() => document.body.style.overflow)).toBe('hidden')
    await publish.getByRole('button', { name: 'Để sau' }).click()
    await expect(publish).toBeHidden()
    expect(await page.evaluate(() => document.body.style.overflow)).not.toBe('hidden')

    await page.getByRole('button', { name: 'Thao tác với Nhập môn Machine Learning cho sinh viên năm hai' }).click()
    await page.getByRole('menuitem', { name: 'Hủy workshop' }).click()
    const cancel = page.getByRole('alertdialog', { name: 'Hủy workshop này?' })
    await expect(cancel).toContainText('Đăng ký hiện có không bị hủy tự động.')
    // The policy is checked before the screenshot: a screenshot makes the browser inject a style of its own (WebKit reports it).
    expect(health.cspViolations).toEqual([])
    expect(await page.evaluate(() => window.__cspViolations ?? [])).toEqual([])
    await shot(page, 'list-1280', testInfo)
    await cancel.getByRole('button', { name: 'Giữ workshop' }).click()
  })

  test('768 px: the icon rail keeps every link named, the table scrolls inside itself, the page does not', async ({ page, health }, testInfo) => {
    await page.setViewportSize({ width: 768, height: 900 })
    await signedIn(page, MANAGER)
    await api(page, { 'GET /api/v1/admin/workshops': { body: pageOf([summary(), summary({ id: DRAFT_ID, title: 'Bản nháp A', status: 'DRAFT' })]) } })
    await page.goto('/admin/workshops')

    await expect(page.getByRole('table', { name: 'Danh sách workshop' })).toBeVisible()
    await expect(page.getByRole('link', { name: 'Danh sách workshop' })).toBeVisible()
    await expect(page.getByRole('link', { name: 'Tạo workshop' }).first()).toBeVisible()
    expect(await noHorizontalScroll(page)).toBe(true)
    expect(await page.evaluate(() => window.__cspViolations ?? [])).toEqual([])
    expect(health.cspViolations).toEqual([])
    await shot(page, 'list-768', testInfo)
  })
})

test.describe('create (S11)', () => {
  // The browser is told it is in Los Angeles: a `datetime-local` value has no zone, and it must still mean Vietnam (D161).
  test.use({ timezoneId: 'America/Los_Angeles' })

  test('reads the times as Vietnam time whatever the browser zone is, and opens the draft', async ({ page }, testInfo) => {
    await page.setViewportSize({ width: 1280, height: 1000 })
    await signedIn(page, MANAGER)
    let body: Record<string, unknown> | null = null
    await api(page, {
      'POST /api/v1/workshops': (request) => {
        body = request.postDataJSON() as Record<string, unknown>
        return { status: 201, body: detail({ id: DRAFT_ID, status: 'DRAFT', seatsTaken: 0 }) }
      },
      [`GET /api/v1/workshops/${DRAFT_ID}`]: { body: detail({ id: DRAFT_ID, status: 'DRAFT', seatsTaken: 0 }) },
    })
    await page.goto('/admin/workshops/new')

    await page.getByLabel(/^Tên workshop/).fill('Cloud Computing với AWS')
    await page.getByLabel(/^Diễn giả/).fill('Nguyễn Thị Thu Hà')
    await page.getByLabel(/^Địa điểm/).fill('Phòng B1.203')
    await page.getByLabel(/^Bắt đầu/).fill('2026-11-18T13:30')
    await page.getByLabel(/^Kết thúc/).fill('2026-11-18T12:00')
    await page.getByLabel(/^Sức chứa/).fill('40')
    await page.getByRole('button', { name: 'Tạo bản nháp' }).click()
    await expect(page.getByText('Giờ kết thúc phải sau giờ bắt đầu.')).toBeVisible()
    await shot(page, 'create-error', testInfo)

    await page.getByLabel(/^Kết thúc/).fill('2026-11-18T16:00')
    await page.getByRole('button', { name: 'Tạo bản nháp' }).click()

    await expect(page).toHaveURL(new RegExp(`/admin/workshops/${DRAFT_ID}$`))
    expect(body).toMatchObject({ startTime: '2026-11-18T06:30:00.000Z', endTime: '2026-11-18T09:00:00.000Z', capacity: 40, isPaid: false, price: null })
    await expect(page.getByRole('status').filter({ hasText: 'Đã tạo bản nháp.' })).toBeVisible()
  })
})

test.describe('cover and brochure (S13, S14)', () => {
  test('a chosen image is previewed from a data: URL and sent to the same origin with only its Content-Type; no policy violation', async ({ page, health }, testInfo) => {
    await page.setViewportSize({ width: 1280, height: 1100 })
    await signedIn(page, MANAGER)
    const put: { method: string; contentType: string | null; authorization: string | null; size: number }[] = []
    await api(page, {
      [`GET /api/v1/workshops/${WORKSHOP_ID}`]: { body: detail({ status: 'DRAFT', seatsTaken: 0 }) },
      [`POST /api/v1/workshops/${WORKSHOP_ID}/cover-image-upload-url`]: (request) => {
        const sent = request.postDataJSON() as { contentType: string; contentLength: number }
        expect(sent).toEqual({ contentType: 'image/png', contentLength: PNG.length })
        return { body: { uploadUrl: `${new URL(request.url()).origin}/unihub-brochures/covers/${WORKSHOP_ID}/k.png`, expiresAt: '2026-11-09T10:00:00Z' } }
      },
      [`POST /api/v1/workshops/${WORKSHOP_ID}/cover-image-upload-confirm`]: { body: { coverImageUrl: `/media/covers/${WORKSHOP_ID}/k.png` } },
    })
    await page.route('**/unihub-brochures/**', (route) => {
      const request = route.request()
      put.push({
        method: request.method(),
        contentType: request.headers()['content-type'] ?? null,
        authorization: request.headers()['authorization'] ?? null,
        size: request.postDataBuffer()?.length ?? 0,
      })
      return route.fulfill({ status: 200, body: '' })
    })
    await page.route('**/media/covers/**', (route) => route.fulfill({ status: 200, contentType: 'image/png', body: PNG }))
    await page.goto(`/admin/workshops/${WORKSHOP_ID}`)

    const card = page.getByRole('region', { name: 'Ảnh bìa' })
    await card.getByLabel('Chọn tệp ảnh bìa').setInputFiles({ name: 'hoi-truong.png', mimeType: 'image/png', buffer: PNG })
    const preview = card.getByRole('img', { name: 'Xem trước ảnh đã chọn' })
    await expect(preview).toBeVisible()
    expect(await preview.getAttribute('src')).toMatch(/^data:image\/png;base64,/)
    await expect.poll(() => preview.evaluate((img: HTMLImageElement) => img.complete && img.naturalWidth > 0)).toBe(true)

    await card.getByRole('button', { name: 'Tải lên' }).click()
    await expect(card.getByText('Đã cập nhật ảnh bìa.')).toBeVisible()
    expect(put.map(({ size: _size, ...rest }) => rest)).toEqual([{ method: 'PUT', contentType: 'image/png', authorization: null }])
    // WebKit does not hand a route the body of an XMLHttpRequest sent from a Blob; the other two engines do, and the size is exact.
    if (testInfo.project.name !== 'webkit') expect(put[0].size).toBe(PNG.length)

    expect(await page.evaluate(() => window.__cspViolations ?? [])).toEqual([])
    expect(health.cspViolations).toEqual([])
    await shot(page, 'cover-uploaded', testInfo)
  })

  test('a file that is not an acceptable image is stopped in the page, with no request', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 1100 })
    await signedIn(page, MANAGER)
    let asked = 0
    await api(page, {
      [`GET /api/v1/workshops/${WORKSHOP_ID}`]: { body: detail({ status: 'DRAFT', seatsTaken: 0 }) },
      [`POST /api/v1/workshops/${WORKSHOP_ID}/cover-image-upload-url`]: () => ((asked += 1), { body: {} }),
    })
    await page.goto(`/admin/workshops/${WORKSHOP_ID}`)

    await page
      .getByRole('region', { name: 'Ảnh bìa' })
      .getByLabel('Chọn tệp ảnh bìa')
      .setInputFiles({ name: 'logo.svg', mimeType: 'image/svg+xml', buffer: Buffer.from('<svg xmlns="http://www.w3.org/2000/svg"/>') })

    await expect(page.getByRole('region', { name: 'Ảnh bìa' }).getByRole('alert')).toContainText('SVG và GIF không được dùng')
    expect(asked).toBe(0)
  })
})

test.describe('report (S15)', () => {
  test('the ring is drawn without a style attribute: its arc has a stroke and a dash, and the page is quiet', async ({ page, health }, testInfo) => {
    await page.setViewportSize({ width: 1280, height: 1000 })
    await signedIn(page, MANAGER)
    await api(page, {
      [`GET /api/v1/workshops/${WORKSHOP_ID}/report`]: {
        body: { workshopId: WORKSHOP_ID, capacity: 150, seatsTaken: 132, confirmedCount: 120, reservedCount: 12, checkedInCount: 86, checkInRate: 86 / 120 },
      },
      [`GET /api/v1/workshops/${WORKSHOP_ID}`]: { body: detail() },
      [`GET /api/v1/workshops/${WORKSHOP_ID}/attendances`]: {
        body: { items: [{ registrationId: '3f9a21c4-0000-4000-8000-000000000001', checkedInAt: '2026-11-15T06:32:07Z' }], page: { page: 0, size: 20, totalElements: 1, totalPages: 1 } },
      },
    })
    await page.goto(`/admin/workshops/${WORKSHOP_ID}/report`)

    const region = page.getByRole('region', { name: 'Báo cáo workshop' })
    await expect(region.getByRole('progressbar', { name: 'Tỷ lệ check-in' })).toHaveAttribute('aria-valuenow', '72')
    await expect(region.getByText('72%')).toBeVisible()
    const arc = region.locator('circle').nth(1)
    const style = await arc.evaluate((circle) => {
      const computed = getComputedStyle(circle)
      return { stroke: computed.stroke, dash: circle.getAttribute('stroke-dasharray') }
    })
    expect(style.stroke).not.toBe('none')
    expect(style.dash).toMatch(/^\d/)
    await expect(page.getByRole('region', { name: 'Danh sách điểm danh' }).getByText('15/11/2026 13:32:07')).toBeVisible()
    expect(await noHorizontalScroll(page)).toBe(true)
    expect(await page.evaluate(() => window.__cspViolations ?? [])).toEqual([])
    expect(health.cspViolations).toEqual([])
    await shot(page, 'report-1280', testInfo)
  })
})
