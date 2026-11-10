import type { Page } from '@playwright/test'
import { mkdirSync } from 'node:fs'

import type { TestInfo } from '@playwright/test'

import { test, expect } from '../support/local-fixtures'
import type { PageHealth } from '../support/page-health'

// Phase 8, G3 (checklist 5.2–5.6, 8.4): the checkout, registrations, e-ticket and inbox pages in a real browser at the widths
// they promise, under the production Content-Security-Policy, with the API answered by `page.route`. jsdom applies no CSS,
// so what is checked here is what Vitest cannot see: a sticky bar that sticks, a column that exists only from `lg`, a QR that
// stays white on a dark page, no sideways scroll. The screenshots go to test-results/g3-screens/ for a person to look at.

const SHOTS = 'test-results/g3-screens'
mkdirSync(SHOTS, { recursive: true })

const REGISTRATION_ID = '5b0c9d2e-1f3a-4b6c-8d7e-9f0a1b2c3d4e'
const WORKSHOP_ID = '3f2b8c1e-6d4a-4a57-9b0e-1c2d3e4f5a6b'

// The size of a real ticket (260 characters, version 15).
const QR_PAYLOAD = `eyJhbGciOiJFUzI1NiJ9.${'p'.repeat(152)}.${'s'.repeat(86)}`

const inMinutes = (minutes: number) => new Date(Date.now() + minutes * 60_000).toISOString()

const workshop = {
  id: WORKSHOP_ID,
  title: 'Thiết kế hệ thống chịu tải: từ lý thuyết đến thực tế',
  description: 'Buổi chia sẻ về các quyết định thiết kế khi một hệ thống phục vụ hàng nghìn người cùng lúc.',
  speakerName: 'ThS. Phạm Thu Hà',
  speakerBio: null,
  location: 'Hội trường B',
  startTime: '2026-11-11T14:00:00+07:00',
  endTime: '2026-11-11T16:30:00+07:00',
  capacity: 60,
  seatsTaken: 52,
  isPaid: true,
  price: 150000,
  status: 'PUBLISHED',
  aiSummary: null,
  coverImageUrl: null,
  version: 1,
}

const registration = (status: string, extra: Record<string, unknown> = {}) => ({
  id: REGISTRATION_ID,
  workshopId: WORKSHOP_ID,
  userId: '7a1d2c3e-4b5f-4a6b-8c9d-0e1f2a3b4c5d',
  ticketType: 'PAID',
  status,
  holdExpiresAt: null,
  ticketCode: null,
  qrPayload: null,
  createdAt: '2026-11-09T08:14:00+07:00',
  updatedAt: '2026-11-09T08:14:00+07:00',
  ...extra,
})

const json = (body: unknown, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) })

async function signedIn(page: Page) {
  await page.addInitScript(() => {
    sessionStorage.setItem(
      'unihub.session',
      JSON.stringify({
        accessToken: 'opaque',
        expiresAt: Date.now() + 3_600_000,
        user: { id: '7a1d2c3e-4b5f-4a6b-8c9d-0e1f2a3b4c5d', fullName: 'Nguyễn Văn An', email: 'an@student.test', roles: ['Student'] },
        permissions: ['REGISTRATION_CREATE'],
      }),
    )
  })
}

async function serve(page: Page, routes: Record<string, unknown>) {
  await page.route('**/api/v1/**', (route) => {
    const path = new URL(route.request().url()).pathname
    for (const [prefix, body] of Object.entries(routes)) {
      if (path === prefix || path.startsWith(`${prefix}/`) || path.startsWith(prefix)) return route.fulfill(json(body))
    }
    return route.fulfill(json({ type: 'about:blank', title: 'Not found', status: 404 }, 404))
  })
}

const noHorizontalScroll = (page: Page) =>
  page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)

const page_of = (items: unknown[]) => ({ items, page: { page: 0, size: 20, totalElements: items.length, totalPages: 1 } })

// The one tolerated exception (owner, 2026-11-06; support/fixtures.ts `pageHealth` is where it lives for the cluster suite):
// WebKit reports `style-src-attr` with an empty sample whenever a page creates a native <select>, and the checkout's
// test-token dropdown is one. At most two reports per <select> that got into the page, on a checkout page and only in
// WebKit; anything else is a violation. This is that exception, not a new one: it is repeated here only because the
// preview server is plain http, which the cluster predicate's `https://` does not match.
async function unexpected(page: Page, health: PageHealth, testInfo: TestInfo): Promise<string[]> {
  const seen = await page.evaluate(() => ({ violations: window.__cspViolations ?? [], selects: window.__selectsCreated ?? 0 }))
  const all = [...health.cspViolations, ...seen.violations]
  const quirk = (entry: string) =>
    testInfo.project.name === 'webkit' && /^style-src-attr blocked inline on \/me\/checkout\/[^ ]+ at https?:\/\/[^ ]+\/assets\/[^ ]+\.js:\d+:\d+ \[\]$/.test(entry)
  const candidates = all.filter(quirk)
  return [...all.filter((entry) => !quirk(entry)), ...candidates.slice(seen.selects * 2)]
}

const shot = (page: Page, name: string, testInfo: { project: { name: string } }) =>
  page.screenshot({ path: `${SHOTS}/${testInfo.project.name}-${name}.png`, fullPage: true })

test.describe('checkout', () => {
  test('1280 px: the clock and the steps are the right column, and the form is beside them', async ({ page, health }, testInfo) => {
    await page.setViewportSize({ width: 1280, height: 900 })
    await signedIn(page)
    await serve(page, {
      [`/api/v1/registrations/${REGISTRATION_ID}`]: registration('RESERVED', { holdExpiresAt: inMinutes(14) }),
      [`/api/v1/workshops/${WORKSHOP_ID}`]: workshop,
    })
    await page.goto(`/me/checkout/${REGISTRATION_ID}`)

    const timer = page.getByRole('timer')
    await expect(timer).toBeVisible()
    const steps = page.getByRole('list', { name: 'Các bước' })
    await expect(steps).toBeVisible()
    // Side by side: the clock is to the right of the summary of the workshop.
    const summary = page.getByRole('region', { name: 'Workshop bạn đăng ký' })
    const [t, s] = [await timer.boundingBox(), await summary.boundingBox()]
    expect(t!.x).toBeGreaterThan(s!.x + s!.width - 1)
    await expect(page.getByRole('heading', { name: 'Thanh toán' })).toBeVisible()
    // The payment form is there (a test-mode build): the dropdown by its label and one button, in the flow of the page.
    await expect(page.getByLabel('Phương thức thanh toán (chế độ thử nghiệm)')).toHaveValue('pm_card_visa')
    const pay = page.getByRole('button', { name: 'Thanh toán' })
    await expect(pay).toHaveCount(1)
    await expect(pay).toBeVisible()
    await expect(page.getByText('Tổng cộng')).toBeHidden()
    expect(await noHorizontalScroll(page)).toBe(true)
    // The policy is checked before the screenshot: a screenshot makes the browser inject a style of its own (WebKit reports it).
    const violations = await unexpected(page, health, testInfo)
    await shot(page, 'checkout-1280', testInfo)
    expect(violations).toEqual([])
  })

  test('360 px: the clock bar and the payment bar stay in sight while the page scrolls, and there are no steps', async ({ page, health }, testInfo) => {
    await page.setViewportSize({ width: 360, height: 740 })
    await signedIn(page)
    await serve(page, {
      [`/api/v1/registrations/${REGISTRATION_ID}`]: registration('RESERVED', { holdExpiresAt: inMinutes(14) }),
      [`/api/v1/workshops/${WORKSHOP_ID}`]: workshop,
    })
    await page.goto(`/me/checkout/${REGISTRATION_ID}`)

    const timer = page.getByRole('timer')
    await expect(timer).toBeVisible()
    await expect(page.getByRole('list', { name: 'Các bước' })).toBeHidden()
    // The one "Thanh toán" button is in sight as the page opens (a bar stuck to the bottom edge, with the total) and stays
    // there: before and after scrolling to the end.
    const pay = page.getByRole('button', { name: 'Thanh toán' })
    await expect(pay).toHaveCount(1)
    await expect(pay).toBeInViewport({ ratio: 1 })
    await expect(page.getByText('Tổng cộng')).toBeVisible()
    // The policy is checked before the first screenshot: a screenshot makes the browser inject a style of its own (WebKit reports it).
    const violations = await unexpected(page, health, testInfo)
    await shot(page, 'checkout-360-top', testInfo)
    await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight))
    await expect(timer).toBeInViewport()
    await expect(pay).toBeInViewport({ ratio: 1 })
    const box = await pay.boundingBox()
    expect(box!.height).toBeGreaterThanOrEqual(44)
    expect(await noHorizontalScroll(page)).toBe(true)
    await shot(page, 'checkout-360', testInfo)
    expect(violations).toEqual([])
  })
})

test.describe('e-ticket', () => {
  const confirmed = registration('CONFIRMED', { ticketCode: '7K3M9Q2XH5T8W1RZ4B6D0N8VPE', qrPayload: QR_PAYLOAD })

  test('360 px on a dark system: the QR panel stays white and the symbol is between 256 and 288 px wide', async ({ page, health }, testInfo) => {
    await page.emulateMedia({ colorScheme: 'dark' })
    await page.setViewportSize({ width: 360, height: 740 })
    await signedIn(page)
    await serve(page, {
      [`/api/v1/registrations/${REGISTRATION_ID}`]: confirmed,
      [`/api/v1/workshops/${WORKSHOP_ID}`]: workshop,
    })
    await page.goto(`/me/registrations/${REGISTRATION_ID}`)

    const qr = page.getByRole('img', { name: 'Mã QR e-ticket' })
    await expect(qr).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.classList.contains('dark'))).toBe(true)
    const panel = await qr.evaluate((el) => getComputedStyle(el.parentElement!).backgroundColor)
    expect(panel).toBe('rgb(255, 255, 255)')
    const box = await qr.boundingBox()
    expect(box!.width).toBeGreaterThanOrEqual(256)
    expect(box!.width).toBeLessThanOrEqual(288.5)
    await expect(page.getByText('Tăng độ sáng màn hình khi đưa vé cho nhân viên.')).toBeVisible()
    expect(await noHorizontalScroll(page)).toBe(true)
    // The policy is checked before the screenshot: a screenshot makes the browser inject a style of its own (WebKit reports it).
    const violations = await unexpected(page, health, testInfo)
    await shot(page, 'ticket-360-dark', testInfo)
    expect(violations).toEqual([])
  })

  test('1280 px: the ticket is two halves side by side, the brightness hint is gone, the QR is 256 px', async ({ page, health }, testInfo) => {
    await page.setViewportSize({ width: 1280, height: 900 })
    await signedIn(page)
    await serve(page, {
      [`/api/v1/registrations/${REGISTRATION_ID}`]: confirmed,
      [`/api/v1/workshops/${WORKSHOP_ID}`]: workshop,
    })
    await page.goto(`/me/registrations/${REGISTRATION_ID}`)

    const qr = page.getByRole('img', { name: 'Mã QR e-ticket' })
    await expect(qr).toBeVisible()
    const box = await qr.boundingBox()
    expect(Math.round(box!.width)).toBe(256)
    await expect(page.getByText('Tăng độ sáng màn hình khi đưa vé cho nhân viên.')).toBeHidden()
    const title = await page.getByRole('link', { name: workshop.title }).boundingBox()
    expect(title!.x + title!.width).toBeLessThan(box!.x)
    expect(await noHorizontalScroll(page)).toBe(true)
    // The policy is checked before the screenshot: a screenshot makes the browser inject a style of its own (WebKit reports it).
    const violations = await unexpected(page, health, testInfo)
    await shot(page, 'ticket-1280', testInfo)
    expect(violations).toEqual([])
  })
})

test.describe('registrations and inbox', () => {
  const rows = [
    registration('RESERVED', { id: 'a1', holdExpiresAt: inMinutes(10) }),
    registration('CONFIRMED', { id: 'a2' }),
    registration('EXPIRED', { id: 'a3', ticketType: 'FREE' }),
  ]
  const notifications = [
    { id: 'n3', type: 'WORKSHOP_RESCHEDULED', title: `Workshop đổi lịch: ${workshop.title}`, body: 'Workshop đã đổi giờ.', relatedEntityType: 'WORKSHOP', relatedEntityId: WORKSHOP_ID, status: 'SENT', createdAt: '2026-11-10T16:20:00+07:00' },
    { id: 'n2', type: 'REGISTRATION_CONFIRMED', title: `Đăng ký thành công: ${workshop.title}`, body: 'Bạn đã đăng ký thành công.', relatedEntityType: 'REGISTRATION', relatedEntityId: 'a2', status: 'SENT', createdAt: '2026-11-09T08:16:00+07:00' },
    { id: 'n1', type: 'WORKSHOP_CANCELLED', title: `Workshop đã bị hủy: ${workshop.title}`, body: 'Workshop đã bị hủy.', relatedEntityType: 'WORKSHOP', relatedEntityId: WORKSHOP_ID, status: 'SENT', createdAt: '2026-11-08T10:02:00+07:00' },
  ]

  for (const width of [1280, 360]) {
    test(`${width} px: the registrations list fits, each row has its status in words and one main action`, async ({ page, health }, testInfo) => {
      await page.setViewportSize({ width, height: 800 })
      await signedIn(page)
      await serve(page, {
        '/api/v1/registrations': page_of(rows),
        [`/api/v1/workshops/${WORKSHOP_ID}`]: workshop,
      })
      await page.goto('/me/registrations')

      const items = page.getByRole('listitem')
      await expect(items).toHaveCount(3)
      await expect(items.nth(0).getByText('Đang giữ chỗ')).toBeVisible()
      await expect(items.nth(0).getByRole('link', { name: 'Thanh toán' })).toBeVisible()
      await expect(items.nth(1).getByRole('link', { name: 'Xem vé' })).toBeVisible()
      await expect(items.nth(2).getByText('Hết hạn giữ chỗ')).toBeVisible()
      const action = await items.nth(1).getByRole('link', { name: 'Xem vé' }).boundingBox()
      expect(action!.height).toBeGreaterThanOrEqual(44)
      expect(await noHorizontalScroll(page)).toBe(true)
      // The policy is checked before the screenshot: a screenshot makes the browser inject a style of its own (WebKit reports it).
      const violations = await unexpected(page, health, testInfo)
      await shot(page, `registrations-${width}`, testInfo)
      expect(violations).toEqual([])
    })

    test(`${width} px: the inbox fits, with a word and an icon per kind and a link where one belongs`, async ({ page, health }, testInfo) => {
      await page.setViewportSize({ width, height: 800 })
      await signedIn(page)
      await serve(page, { '/api/v1/notifications': page_of(notifications) })
      await page.goto('/me/notifications')

      const items = page.getByRole('listitem')
      await expect(items).toHaveCount(3)
      await expect(items.nth(0).getByText('Đổi lịch', { exact: true })).toBeVisible()
      await expect(items.nth(0).getByRole('link', { name: 'Xem workshop' })).toBeVisible()
      await expect(items.nth(1).getByRole('link', { name: 'Xem vé' })).toBeVisible()
      await expect(items.nth(2).getByText('Đã hủy', { exact: true })).toBeVisible()
      expect(await noHorizontalScroll(page)).toBe(true)
      // The policy is checked before the screenshot: a screenshot makes the browser inject a style of its own (WebKit reports it).
      const violations = await unexpected(page, health, testInfo)
      await shot(page, `inbox-${width}`, testInfo)
      expect(violations).toEqual([])
    })
  }
})
