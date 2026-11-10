import AxeBuilder from '@axe-core/playwright'
import { expect, test as base, type Page } from '@playwright/test'

import { watch, type PageHealth } from '../support/page-health'

// Phase 8, task 7.4 (D115: WCAG 2.2 AA is the floor). Evidence for M-86. Two halves:
//
//   1. axe-core over every screen of the Web, in both themes and at 360 and 1280 px, on the three engines: names, roles, labels,
//      landmarks and colour contrast (the contrast of both themes is measured here, on the pages as drawn, not only on the token
//      table). axe cannot judge everything (focus order, whether a live region speaks too often), so
//   2. the keyboard and the live regions are walked by hand below: tab order, a focus indicator you can see, menus and dialogs that
//      take focus in and give it back, and the seat count that is not announced on every refetch.
//
// The API is answered by `page.route`; vite preview serves the production build, so the Content-Security-Policy is the real one.

const test = base.extend<{ health: PageHealth }>({
  health: async ({ page }, use) => {
    await use(await watch(page))
  },
})

const WORKSHOP_ID = '3f2b8c1e-6d4a-4a57-9b0e-1c2d3e4f5a6b'
const REGISTRATION_ID = '5b0c9d2e-1f3a-4b6c-8d7e-9f0a1b2c3d4e'
const DRAFT_ID = '9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d'
const USER_ID = '7a1d2c3e-4b5f-4a6b-8c9d-0e1f2a3b4c5d'

const inMinutes = (minutes: number) => new Date(Date.now() + minutes * 60_000).toISOString()
const json = (body: unknown, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) })
const pageOf = (items: unknown[]) => ({ items, page: { page: 0, size: 20, totalElements: items.length, totalPages: 1 } })

const summary = (overrides: Record<string, unknown> = {}) => ({
  id: WORKSHOP_ID,
  title: 'Thiết kế hệ thống chịu tải: từ lý thuyết đến thực tế',
  location: 'Hội trường B',
  startTime: '2026-11-14T06:30:00Z',
  endTime: '2026-11-14T09:30:00Z',
  status: 'PUBLISHED',
  speakerName: 'ThS. Phạm Thu Hà',
  capacity: 60,
  seatsTaken: 52,
  isPaid: true,
  price: 150000,
  coverImageUrl: null,
  ...overrides,
})
const detail = (overrides: Record<string, unknown> = {}) => ({
  ...summary(),
  description: 'Buổi chia sẻ về các quyết định thiết kế khi một hệ thống phục vụ hàng nghìn người cùng lúc.',
  aiSummary: 'Bài nói đi từ giới hạn của một máy chủ đến cách chia tải, hàng đợi và bộ nhớ đệm.',
  speakerBio: 'Giảng viên khoa Công nghệ thông tin, mười năm làm hệ thống phân tán.',
  hasBrochure: true,
  version: 1,
  ...overrides,
})
const registration = (status: string, extra: Record<string, unknown> = {}) => ({
  id: REGISTRATION_ID,
  workshopId: WORKSHOP_ID,
  userId: USER_ID,
  ticketType: 'PAID',
  status,
  holdExpiresAt: null,
  ticketCode: null,
  qrPayload: null,
  createdAt: '2026-11-09T08:14:00+07:00',
  updatedAt: '2026-11-09T08:14:00+07:00',
  ...extra,
})
const confirmed = registration('CONFIRMED', {
  ticketCode: '0123456789ABCDEFGHJKMNPQRS',
  qrPayload: `eyJhbGciOiJFUzI1NiJ9.${'p'.repeat(152)}.${'s'.repeat(86)}`,
})
const notifications = [
  { id: 'n2', type: 'REGISTRATION_CONFIRMED', title: 'Đăng ký thành công', body: 'Bạn đã đăng ký thành công.', relatedEntityType: 'REGISTRATION', relatedEntityId: REGISTRATION_ID, status: 'SENT', createdAt: '2026-11-09T08:16:00+07:00' },
  { id: 'n1', type: 'WORKSHOP_CANCELLED', title: 'Workshop đã bị hủy', body: 'Workshop đã bị hủy.', relatedEntityType: 'WORKSHOP', relatedEntityId: WORKSHOP_ID, status: 'SENT', createdAt: '2026-11-08T10:02:00+07:00' },
]

/** Answers exact paths; anything else is a 404 problem. */
async function serve(page: Page, routes: Record<string, unknown>) {
  await page.route('**/api/v1/**', (route) => {
    const path = new URL(route.request().url()).pathname
    return route.fulfill(path in routes ? json(routes[path]) : json({ type: 'about:blank', title: 'Not found', status: 404 }, 404))
  })
}

async function signedIn(page: Page, permissions: string[], roles = ['Student']) {
  await page.addInitScript(
    ([granted, rs]) => {
      sessionStorage.setItem(
        'unihub.session',
        JSON.stringify({
          accessToken: 'opaque',
          expiresAt: Date.now() + 3_600_000,
          user: { id: '7a1d2c3e-4b5f-4a6b-8c9d-0e1f2a3b4c5d', fullName: 'Nguyễn Văn An', email: 'an@student.test', roles: rs },
          permissions: granted,
        }),
      )
    },
    [permissions, roles] as const,
  )
}

const theme = (page: Page, value: 'light' | 'dark') => page.addInitScript((v) => window.localStorage.setItem('unihub.theme', v), value)

type Screen = { name: string; path: string; as: 'visitor' | 'student' | 'manager'; routes: Record<string, unknown>; ready: (page: Page) => Promise<unknown> }

const STUDENT = ['REGISTRATION_CREATE']
const MANAGER = ['WORKSHOP_CREATE', 'WORKSHOP_EDIT', 'REPORT_VIEW']

const SCREENS: Screen[] = [
  { name: 'sign in', path: '/login', as: 'visitor', routes: {}, ready: (p) => p.getByRole('button', { name: 'Đăng nhập' }).waitFor() },
  {
    name: 'catalog',
    path: '/workshops',
    as: 'visitor',
    routes: { '/api/v1/workshops': pageOf([summary(), summary({ id: DRAFT_ID, title: 'Nhập môn Machine Learning', isPaid: false, price: null, seatsTaken: 10 })]) },
    ready: (p) => p.getByRole('link', { name: 'Nhập môn Machine Learning' }).waitFor(),
  },
  {
    name: 'workshop detail',
    path: `/workshops/${WORKSHOP_ID}`,
    as: 'student',
    routes: { [`/api/v1/workshops/${WORKSHOP_ID}`]: detail(), '/api/v1/registrations': pageOf([]) },
    ready: (p) => p.getByText(/Còn \d+ \/ 60 chỗ/).waitFor(),
  },
  {
    name: 'checkout',
    path: `/me/checkout/${REGISTRATION_ID}`,
    as: 'student',
    routes: { [`/api/v1/registrations/${REGISTRATION_ID}`]: registration('RESERVED', { holdExpiresAt: inMinutes(14) }), [`/api/v1/workshops/${WORKSHOP_ID}`]: detail() },
    ready: (p) => p.getByRole('button', { name: 'Thanh toán' }).waitFor(),
  },
  {
    name: 'my registrations',
    path: '/me/registrations',
    as: 'student',
    routes: {
      '/api/v1/registrations': pageOf([registration('RESERVED', { id: 'a1', holdExpiresAt: inMinutes(10) }), registration('CONFIRMED', { id: 'a2' }), registration('EXPIRED', { id: 'a3', ticketType: 'FREE' })]),
      [`/api/v1/workshops/${WORKSHOP_ID}`]: detail(),
    },
    ready: (p) => p.getByRole('link', { name: 'Xem vé' }).waitFor(),
  },
  {
    name: 'e-ticket',
    path: `/me/registrations/${REGISTRATION_ID}`,
    as: 'student',
    routes: { [`/api/v1/registrations/${REGISTRATION_ID}`]: confirmed, [`/api/v1/workshops/${WORKSHOP_ID}`]: detail() },
    ready: (p) => p.getByRole('img', { name: 'Mã QR e-ticket' }).waitFor(),
  },
  { name: 'inbox', path: '/me/notifications', as: 'student', routes: { '/api/v1/notifications': pageOf(notifications) }, ready: (p) => p.getByText('Đăng ký thành công').first().waitFor() },
  {
    name: 'admin list',
    path: '/admin/workshops',
    as: 'manager',
    routes: { '/api/v1/admin/workshops': pageOf([summary(), summary({ id: DRAFT_ID, title: 'Bản nháp A', status: 'DRAFT', seatsTaken: 0 })]) },
    ready: (p) => p.getByRole('table').waitFor(),
  },
  { name: 'admin create', path: '/admin/workshops/new', as: 'manager', routes: {}, ready: (p) => p.getByLabel('Tên workshop').waitFor() },
  {
    name: 'admin edit',
    path: `/admin/workshops/${WORKSHOP_ID}`,
    as: 'manager',
    routes: { [`/api/v1/workshops/${WORKSHOP_ID}`]: detail() },
    ready: (p) => p.getByLabel('Tên workshop').waitFor(),
  },
  {
    name: 'admin report',
    path: `/admin/workshops/${WORKSHOP_ID}/report`,
    as: 'manager',
    routes: {
      [`/api/v1/workshops/${WORKSHOP_ID}/report`]: { workshopId: WORKSHOP_ID, capacity: 150, seatsTaken: 132, confirmedCount: 120, reservedCount: 12, checkedInCount: 86, checkInRate: 86 / 120 },
      [`/api/v1/workshops/${WORKSHOP_ID}`]: detail(),
      [`/api/v1/workshops/${WORKSHOP_ID}/attendances`]: pageOf([{ registrationId: '3f9a21c4-0000-4000-8000-000000000001', checkedInAt: '2026-11-15T06:32:07Z' }]),
    },
    ready: (p) => p.getByRole('region', { name: 'Báo cáo workshop' }).waitFor(),
  },
]

async function open(page: Page, screen: Screen, mode: 'light' | 'dark', width: number) {
  await page.setViewportSize({ width, height: 900 })
  await theme(page, mode)
  if (screen.as === 'student') await signedIn(page, STUDENT)
  if (screen.as === 'manager') await signedIn(page, MANAGER, ['Organizer'])
  await serve(page, screen.routes)
  await page.goto(screen.path)
  await screen.ready(page)
}

/** What axe found, as a short list a person can act on. */
const describeViolations = (violations: Awaited<ReturnType<AxeBuilder['analyze']>>['violations']) =>
  violations.map((v) => `${v.id} (${v.impact}): ${v.help} — ${v.nodes.slice(0, 3).map((n) => n.target.join(' ')).join(' | ')}`)

for (const mode of ['light', 'dark'] as const) {
  for (const width of [360, 1280]) {
    test.describe(`axe, ${mode} theme, ${width} px`, () => {
      for (const screen of SCREENS) {
        test(screen.name, async ({ page, health }, testInfo) => {
          await open(page, screen, mode, width)
          expect(await page.evaluate(() => document.documentElement.classList.contains('dark'))).toBe(mode === 'dark')
          const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']).analyze()
          expect(describeViolations(results.violations)).toEqual([])
          // WebKit's one known report for the checkout's native <select> is judged by student-screens.spec.ts, not here.
          if (!(screen.name === 'checkout' && testInfo.project.name === 'webkit')) expect(health.cspViolations).toEqual([])
        })
      }
    })
  }
}

/** True when the element, focused by the keyboard, draws something that is not the page: an outline or a ring (box-shadow). */
const focusIsVisible = (page: Page) =>
  page.evaluate(() => {
    const el = document.activeElement as HTMLElement | null
    if (!el || el === document.body) return false
    const style = getComputedStyle(el)
    const outline = style.outlineStyle !== 'none' && parseFloat(style.outlineWidth) > 0
    return outline || (style.boxShadow !== 'none' && style.boxShadow !== '')
  })

/**
 * The key that moves to the next control. Safari on macOS skips buttons and links with plain Tab unless "Press Tab to highlight
 * each item" is on (a browser setting, not something a page controls); Option+Tab visits them all, and that is what a Safari
 * user with the default setting presses. Playwright's WebKit on macOS behaves the same way.
 */
const nextKey = (browserName: string) => (browserName === 'webkit' && process.platform === 'darwin' ? 'Alt+Tab' : 'Tab')
const previousKey = (browserName: string) => (browserName === 'webkit' && process.platform === 'darwin' ? 'Alt+Shift+Tab' : 'Shift+Tab')

test.describe('keyboard', () => {
  test.use({ viewport: { width: 1280, height: 900 } })

  test('sign in: Tab walks the fields in reading order and every stop shows where focus is', async ({ page, browserName }) => {
    await page.goto('/login')
    const stops: string[] = []
    for (let i = 0; i < 12 && stops.length < 3; i += 1) {
      await page.keyboard.press(nextKey(browserName))
      const name = await page.evaluate(() => {
        const el = document.activeElement as HTMLElement
        return el.matches('input,button') && el.closest('main') ? (el.getAttribute('aria-label') ?? (el as HTMLInputElement).labels?.[0]?.textContent ?? el.textContent ?? '').trim() : ''
      })
      if (!name) continue
      stops.push(name)
      expect(await focusIsVisible(page), `focus on "${name}"`).toBe(true)
    }
    expect(stops).toEqual(['Email', 'Mật khẩu', 'Đăng nhập'])
  })

  test('the catalog card is one stop whose name is the title, and it shows focus', async ({ page, browserName }) => {
    await serve(page, { '/api/v1/workshops': pageOf([summary()]) })
    await page.goto('/workshops')
    const link = page.getByRole('link', { name: summary().title })
    await link.focus()
    await page.keyboard.press(previousKey(browserName))
    await page.keyboard.press(nextKey(browserName))
    await expect(link).toBeFocused()
    // The ring is on the card (focus-within), the link itself draws none: a person sees the whole card.
    const ring = await link.evaluate((el) => getComputedStyle(el.closest('[data-slot="card"]') ?? el).boxShadow)
    expect(ring).not.toBe('none')
  })

  test('the row menu opens from the keyboard, takes focus, and gives it back on Escape', async ({ page }) => {
    await signedIn(page, MANAGER, ['Organizer'])
    await serve(page, { '/api/v1/admin/workshops': pageOf([summary()]) })
    await page.goto('/admin/workshops')
    const trigger = page.getByRole('button', { name: /Thao tác/ }).first()
    await trigger.focus()
    await page.keyboard.press('Enter')
    const menu = page.getByRole('menu')
    await expect(menu).toBeVisible()
    expect(await page.evaluate(() => !!document.activeElement?.closest('[role="menu"]'))).toBe(true)
    await page.keyboard.press('Escape')
    await expect(menu).toBeHidden()
    await expect(trigger).toBeFocused()
  })

  test('a confirmation dialog traps focus and returns it to what opened it', async ({ page }) => {
    await signedIn(page, MANAGER, ['Organizer'])
    await serve(page, { [`/api/v1/workshops/${WORKSHOP_ID}`]: detail() })
    await page.goto(`/admin/workshops/${WORKSHOP_ID}`)
    const opener = page.getByRole('button', { name: /Hủy workshop/ }).first()
    await opener.focus()
    await page.keyboard.press('Enter')
    const dialog = page.getByRole('alertdialog')
    await expect(dialog).toBeVisible()
    for (let i = 0; i < 8; i += 1) {
      await page.keyboard.press('Tab')
      expect(await page.evaluate(() => !!document.activeElement?.closest('[role="alertdialog"]')), `Tab ${i + 1} stays inside`).toBe(true)
    }
    await page.keyboard.press('Escape')
    await expect(dialog).toBeHidden()
    await expect(opener).toBeFocused()
  })

  test('the phone menu takes focus in and gives it back on Escape', async ({ page }) => {
    await page.setViewportSize({ width: 360, height: 740 })
    await page.goto('/login')
    const trigger = page.getByRole('button', { name: 'Mở menu' })
    await trigger.focus()
    await page.keyboard.press('Enter')
    const sheet = page.getByRole('dialog')
    await expect(sheet).toBeVisible()
    expect(await page.evaluate(() => !!document.activeElement?.closest('[role="dialog"]'))).toBe(true)
    await page.keyboard.press('Escape')
    await expect(sheet).toBeHidden()
    await expect(trigger).toBeFocused()
  })
})

test.describe('live regions', () => {
  test('the live seat count is not in a live region, so a refetch every 5 s does not speak', async ({ page }) => {
    await signedIn(page, STUDENT)
    await serve(page, { [`/api/v1/workshops/${WORKSHOP_ID}`]: detail(), '/api/v1/registrations': pageOf([]) })
    await page.goto(`/workshops/${WORKSHOP_ID}`)
    const count = page.getByText(/Còn \d+ \/ 60 chỗ/)
    await expect(count).toBeVisible()
    expect(await count.evaluate((el) => !!el.closest('[aria-live],[role="status"],[role="alert"],[role="log"],[role="timer"]'))).toBe(false)
  })

  test('the hold countdown is a timer (silent) with one separate announcer, not a region that rewrites every second', async ({ page }) => {
    await signedIn(page, STUDENT)
    await serve(page, {
      [`/api/v1/registrations/${REGISTRATION_ID}`]: registration('RESERVED', { holdExpiresAt: inMinutes(14) }),
      [`/api/v1/workshops/${WORKSHOP_ID}`]: detail(),
    })
    await page.goto(`/me/checkout/${REGISTRATION_ID}`)
    const timer = page.getByRole('timer')
    await expect(timer).toBeVisible()
    const first = await timer.textContent()
    await page.waitForTimeout(2200) // the clock moves...
    expect(await timer.textContent()).not.toBe(first)
    // ...and the status region next to it stays empty until a milestone (15, 10, 5, 1 minutes, expiry).
    const announcer = page.locator('[role="status"].sr-only').last()
    expect(((await announcer.textContent()) ?? '').length).toBeLessThan(80)
    expect(await timer.evaluate((el) => !!el.closest('[aria-live="polite"],[aria-live="assertive"],[role="status"],[role="alert"]'))).toBe(false)
  })
})
