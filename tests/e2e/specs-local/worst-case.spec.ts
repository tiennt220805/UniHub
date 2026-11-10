import type { Page } from '@playwright/test'
import { mkdirSync } from 'node:fs'

import { test, expect } from '../support/local-fixtures'
import type { PageHealth } from '../support/page-health'

// Phase 8, task 7.2 (the `break-ui` pass): the workshop card, the administration list, the e-ticket and the report are fed the
// worst data a real workshop can carry — a 200-character Vietnamese title, a title that is one 120-character word, a very long
// speaker and place, a price and counts of nine digits, a rate above 100%, an empty list — at 360, 768 and 1280 px, on the
// three engines. jsdom has no layout, so only a browser can say a long word pushed a page sideways. The API is answered by
// `page.route`. There is no "worst case" switch in the shipped UI: the data lives here, in the test.
//
// Two questions are asked of every page: does the page scroll sideways, and does any box that is not a scroll container hold
// text wider than itself (a word that leaks out of its card without widening the page, because a parent clips it).

const SHOTS = 'test-results/worst-case'
mkdirSync(SHOTS, { recursive: true })

const WORKSHOP_ID = '3f2b8c1e-6d4a-4a57-9b0e-1c2d3e4f5a6b'
const SECOND_ID = '9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d'
const REGISTRATION_ID = '5b0c9d2e-1f3a-4b6c-8d7e-9f0a1b2c3d4e'

const LONG_TITLE =
  'Hội thảo chuyên đề về ứng dụng trí tuệ nhân tạo và học máy trong phân tích dữ liệu lớn phục vụ ra quyết định quản trị đại học, ' +
  'kèm phiên thảo luận mở giữa giảng viên, nghiên cứu sinh và đại diện doanh nghiệp đối tác của nhà trường'
const ONE_WORD = `Trí${'tuệ-nhân-tạo-'.repeat(9)}hết`
const LONG_SPEAKER = 'GS.TS. Nguyễn Thị Phương Thảo Minh Châu Anh Khoa (Đại học Quốc gia Thành phố Hồ Chí Minh — Trường Đại học Bách khoa)'
const LONG_PLACE = `Giảng đường ${'Nhà-Điều-Hành-Trung-Tâm-'.repeat(5)} tầng 12, phòng 1204`

const summary = (overrides: Record<string, unknown> = {}) => ({
  id: WORKSHOP_ID,
  title: LONG_TITLE,
  location: LONG_PLACE,
  startTime: '2026-11-14T06:30:00Z',
  endTime: '2026-11-14T09:30:00Z',
  status: 'PUBLISHED',
  speakerName: LONG_SPEAKER,
  capacity: 100000,
  seatsTaken: 99999,
  isPaid: true,
  price: 999999999,
  coverImageUrl: null,
  ...overrides,
})
const detail = (overrides: Record<string, unknown> = {}) => ({
  ...summary(),
  description: LONG_TITLE,
  aiSummary: null,
  speakerBio: null,
  hasBrochure: false,
  version: 1,
  ...overrides,
})
const pageOf = (items: unknown[]) => ({ items, page: { page: 0, size: 20, totalElements: items.length, totalPages: 1 } })
const json = (body: unknown, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) })

async function signedIn(page: Page, permissions: string[]) {
  await page.addInitScript((granted) => {
    sessionStorage.setItem(
      'unihub.session',
      JSON.stringify({
        accessToken: 'opaque',
        expiresAt: Date.now() + 3_600_000,
        user: { id: '7a1d2c3e-4b5f-4a6b-8c9d-0e1f2a3b4c5d', fullName: 'Nguyễn Thị Phương Thảo Minh Châu', email: 'a'.repeat(60) + '@student.test', roles: ['Organizer'] },
        permissions: granted,
      }),
    )
  }, permissions)
}

/** Answers by path prefix, longest first, so `/workshops/{id}/report` is not taken for `/workshops/{id}`. */
async function serve(page: Page, routes: Record<string, unknown>) {
  const keys = Object.keys(routes).sort((a, b) => b.length - a.length)
  await page.route('**/api/v1/**', (route) => {
    const path = new URL(route.request().url()).pathname
    const key = keys.find((k) => path === k)
    return route.fulfill(key ? json(routes[key]) : json({ type: 'about:blank', title: 'Not found', status: 404 }, 404))
  })
}

/** The page scrolls sideways, or a non-scrolling box holds a line wider than itself. Returns what leaked, for the message. */
async function leaks(page: Page) {
  return page.evaluate(() => {
    const out: string[] = []
    if (document.documentElement.scrollWidth > document.documentElement.clientWidth) {
      out.push(`page: scrollWidth ${document.documentElement.scrollWidth} > clientWidth ${document.documentElement.clientWidth}`)
    }
    for (const el of Array.from(document.querySelectorAll<HTMLElement>('body *'))) {
      if (el.closest('svg') || el.classList.contains('sr-only')) continue
      if (/^(INPUT|TEXTAREA|SELECT)$/.test(el.tagName)) continue // a field scrolls its own text (WebKit reports it for a long value)
      const style = getComputedStyle(el)
      if (style.display === 'inline' || style.display === 'contents' || el.clientWidth === 0) continue
      if (style.overflowX !== 'visible') continue // a scroll box or a clipped box is a decision, not a leak
      if (el.scrollWidth > el.clientWidth + 1) {
        out.push(`${el.tagName.toLowerCase()}.${String(el.className).slice(0, 40)} "${(el.textContent ?? '').slice(0, 30)}": ${el.scrollWidth} > ${el.clientWidth}`)
      }
    }
    return out
  })
}

const WIDTHS = [360, 768, 1280] as const

for (const width of WIDTHS) {
  test.describe(`${width} px`, () => {
    test.beforeEach(async ({ page }) => {
      await page.setViewportSize({ width, height: 900 })
    })

    test('catalog: long and one-word titles, a long speaker and place, nine-digit price, nearly full', async ({ page, health }, testInfo) => {
      await serve(page, {
        '/api/v1/workshops': pageOf([summary(), summary({ id: SECOND_ID, title: ONE_WORD, speakerName: 'A', location: 'B', price: 0, isPaid: false, seatsTaken: 0, capacity: 0 })]),
      })
      await page.goto('/workshops')
      await expect(page.getByRole('link', { name: ONE_WORD })).toBeVisible()
      expect(await leaks(page)).toEqual([])
      expect(health.cspViolations).toEqual([])
      await page.screenshot({ path: `${SHOTS}/${testInfo.project.name}-catalog-${width}.png`, fullPage: true })
    })

    test('catalog: an empty list says so and does not break the frame', async ({ page }) => {
      await serve(page, { '/api/v1/workshops': pageOf([]) })
      await page.goto('/workshops?q=' + 'x'.repeat(150))
      await expect(page.getByRole('main')).toBeVisible()
      expect(await leaks(page)).toEqual([])
    })

    test('administration list: the same data in the table and its row menu', async ({ page, health }, testInfo) => {
      await signedIn(page, ['WORKSHOP_CREATE', 'WORKSHOP_EDIT', 'REPORT_VIEW'])
      await serve(page, {
        '/api/v1/admin/workshops': pageOf([summary(), summary({ id: SECOND_ID, title: ONE_WORD, status: 'DRAFT', seatsTaken: 0 })]),
      })
      await page.goto('/admin/workshops')
      await expect(page.getByRole('table')).toBeVisible()
      expect(await leaks(page)).toEqual([])
      expect(health.cspViolations).toEqual([])
      await page.screenshot({ path: `${SHOTS}/${testInfo.project.name}-admin-list-${width}.png`, fullPage: true })
    })

    test('administration list: an empty tab and a 150-character search term do not break the frame', async ({ page }) => {
      await signedIn(page, ['WORKSHOP_CREATE', 'WORKSHOP_EDIT', 'REPORT_VIEW'])
      await serve(page, { '/api/v1/admin/workshops': pageOf([]) })
      await page.goto('/admin/workshops?q=' + 'x'.repeat(150))
      await expect(page.getByRole('main')).toBeVisible()
      expect(await leaks(page)).toEqual([])
    })

    test('report: no attendance yet, with a long title and zero counts', async ({ page, health }) => {
      await signedIn(page, ['REPORT_VIEW'])
      await serve(page, {
        [`/api/v1/workshops/${WORKSHOP_ID}/report`]: { workshopId: WORKSHOP_ID, capacity: 0, seatsTaken: 0, confirmedCount: 0, reservedCount: 0, checkedInCount: 0, checkInRate: null },
        [`/api/v1/workshops/${WORKSHOP_ID}`]: detail(),
        [`/api/v1/workshops/${WORKSHOP_ID}/attendances`]: pageOf([]),
      })
      await page.goto(`/admin/workshops/${WORKSHOP_ID}/report`)
      await expect(page.getByRole('region', { name: 'Báo cáo workshop' })).toBeVisible()
      await expect(page.getByRole('region', { name: 'Danh sách điểm danh' })).toBeVisible()
      expect(await leaks(page)).toEqual([])
      expect(health.cspViolations).toEqual([])
    })

    test('e-ticket: a long title and place beside a QR that keeps its size', async ({ page, health }, testInfo) => {
      await signedIn(page, ['REGISTRATION_CREATE'])
      await serve(page, {
        [`/api/v1/registrations/${REGISTRATION_ID}`]: {
          id: REGISTRATION_ID,
          workshopId: WORKSHOP_ID,
          userId: '7a1d2c3e-4b5f-4a6b-8c9d-0e1f2a3b4c5d',
          ticketType: 'PAID',
          status: 'CONFIRMED',
          holdExpiresAt: null,
          ticketCode: '0123456789ABCDEFGHJKMNPQRS',
          qrPayload: `eyJhbGciOiJFUzI1NiJ9.${'p'.repeat(152)}.${'s'.repeat(86)}`,
          createdAt: '2026-11-09T08:14:00+07:00',
          updatedAt: '2026-11-09T08:14:00+07:00',
        },
        [`/api/v1/workshops/${WORKSHOP_ID}`]: detail(),
      })
      await page.goto(`/me/registrations/${REGISTRATION_ID}`)
      await expect(page.getByRole('img', { name: 'Mã QR e-ticket' })).toBeVisible()
      const qr = await page.getByRole('img', { name: 'Mã QR e-ticket' }).boundingBox()
      expect(qr?.width ?? 0).toBeGreaterThanOrEqual(200) // a long title must not squeeze the code a scanner reads
      expect(await leaks(page)).toEqual([])
      expect(health.cspViolations).toEqual([])
      await page.screenshot({ path: `${SHOTS}/${testInfo.project.name}-ticket-${width}.png`, fullPage: true })
    })

    test('report: nine-digit counts, a rate above 100%, a long title, twenty attendance rows', async ({ page, health }, testInfo) => {
      await signedIn(page, ['REPORT_VIEW'])
      const rows = Array.from({ length: 20 }, (_, i) => ({
        registrationId: `3f9a21c4-0000-4000-8000-${String(i).padStart(12, '0')}`,
        checkedInAt: '2026-11-15T06:32:07Z',
      }))
      await serve(page, {
        [`/api/v1/workshops/${WORKSHOP_ID}/report`]: {
          workshopId: WORKSHOP_ID,
          capacity: 999999999,
          seatsTaken: 999999999,
          confirmedCount: 999999999,
          reservedCount: 123456789,
          checkedInCount: 999999999,
          checkInRate: 1.5,
        },
        [`/api/v1/workshops/${WORKSHOP_ID}`]: detail(),
        [`/api/v1/workshops/${WORKSHOP_ID}/attendances`]: { items: rows, page: { page: 0, size: 20, totalElements: 999999999, totalPages: 50000000 } },
      })
      await page.goto(`/admin/workshops/${WORKSHOP_ID}/report`)
      await expect(page.getByRole('region', { name: 'Báo cáo workshop' })).toBeVisible()
      await expect(page.getByRole('progressbar', { name: 'Tỷ lệ check-in' })).toHaveAttribute('aria-valuenow', '100')
      expect(await leaks(page)).toEqual([])
      expect(health.cspViolations).toEqual([])
      await page.screenshot({ path: `${SHOTS}/${testInfo.project.name}-report-${width}.png`, fullPage: true })
    })
  })
}
