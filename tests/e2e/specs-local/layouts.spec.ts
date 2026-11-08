import { expect, test as base, type Page } from '@playwright/test'

import { watch, type PageHealth } from '../support/page-health'

// Task 2.7 (Phase 8 checklist, D128, D136): what the two frames do at the widths they promise. The Vitest tests cannot
// see this, because jsdom applies no CSS: here `md:hidden`, `sr-only lg:not-sr-only` and the rail width are real.
// The API is not running (vite preview proxies /api to nothing), so a page inside the frame shows its error state; the
// frame is what is under test.

const test = base.extend<{ health: PageHealth }>({
  health: async ({ page }, use) => {
    await use(await watch(page))
  },
})

/** A signed-in session in sessionStorage, as the app writes it (features/auth/store/session-store.ts). */
async function signedInAs(page: Page, permissions: string[], roles = ['Student']) {
  await page.addInitScript(
    ([perms, rs]) => {
      sessionStorage.setItem(
        'unihub.session',
        JSON.stringify({
          accessToken: 'opaque',
          expiresAt: Date.now() + 3_600_000,
          user: { id: '7a1d2c3e-4b5f-4a6b-8c9d-0e1f2a3b4c5d', fullName: 'Lê Hoàng An', email: 'an@student.test', roles: rs },
          permissions: perms,
        }),
      )
    },
    [permissions, roles] as const,
  )
}

const noHorizontalScroll = (page: Page) => page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)

test.describe('student frame', () => {
  test('1280 px: the links, the name and sign-out are in the bar; the phone controls are not', async ({ page, health }) => {
    await page.setViewportSize({ width: 1280, height: 800 })
    await signedInAs(page, ['REGISTRATION_CREATE'])
    await page.goto('/me/notifications')
    await expect(page.getByRole('link', { name: 'Đăng ký của tôi' })).toBeVisible()
    await expect(page.getByRole('link', { name: 'Thông báo', exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Đăng xuất' })).toBeVisible()
    await expect(page.getByText('Lê Hoàng An')).toBeVisible()
    for (const name of ['Sáng', 'Tối', 'Theo hệ thống']) await expect(page.getByRole('button', { name })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Mở menu' })).toBeHidden()
    await expect(page.getByRole('link', { name: 'Hộp thư thông báo' })).toBeHidden()
    expect(await noHorizontalScroll(page)).toBe(true)
    expect(health.cspViolations).toEqual([])
  })

  test('360 px: a menu button of 44 px, the bell, a sheet with the links and sign-out; no sideways scroll', async ({ page }) => {
    await page.setViewportSize({ width: 360, height: 740 })
    await signedInAs(page, ['REGISTRATION_CREATE'])
    await page.goto('/me/notifications')
    await expect(page.getByRole('link', { name: 'Đăng ký của tôi' })).toBeHidden()
    await expect(page.getByRole('button', { name: 'Đăng xuất' })).toBeHidden()
    await expect(page.getByRole('link', { name: 'Hộp thư thông báo' })).toBeVisible()
    const menu = page.getByRole('button', { name: 'Mở menu' })
    const box = await menu.boundingBox()
    expect(box?.width ?? 0).toBeGreaterThanOrEqual(44)
    expect(box?.height ?? 0).toBeGreaterThanOrEqual(44)
    await menu.click()
    const sheet = page.getByRole('dialog', { name: 'Menu' })
    await expect(sheet).toBeVisible()
    await expect(sheet.getByRole('link', { name: 'Đăng ký của tôi' })).toBeVisible()
    await expect(sheet.getByRole('button', { name: 'Đăng xuất' })).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(sheet).toBeHidden()
    expect(await noHorizontalScroll(page)).toBe(true)
  })

  test('signed out at 360 px the sheet offers sign-in', async ({ page }) => {
    await page.setViewportSize({ width: 360, height: 740 })
    await page.goto('/login')
    await page.getByRole('button', { name: 'Mở menu' }).click()
    await expect(page.getByRole('dialog', { name: 'Menu' }).getByRole('link', { name: 'Đăng nhập' })).toBeVisible()
  })
})

test.describe('admin frame', () => {
  const REPORT = '/admin/workshops/3f2b8c1e-6d4a-4a57-9b0e-1c2d3e4f5a6b/report'

  test('1280 px: the full sidebar, with the words of its links showing', async ({ page, health }) => {
    await page.setViewportSize({ width: 1280, height: 800 })
    await signedInAs(page, ['REPORT_VIEW'], ['Organizer'])
    await page.goto(REPORT)
    const aside = page.getByRole('complementary', { name: 'Thanh bên quản trị' })
    await expect(aside).toBeVisible()
    expect((await aside.boundingBox())?.width ?? 0).toBeGreaterThanOrEqual(240)
    const home = aside.getByRole('link', { name: 'Về trang sinh viên' })
    expect((await home.boundingBox())?.width ?? 0).toBeGreaterThan(180)
    await expect(aside.getByText('Organizer')).toBeVisible()
    await expect(aside.getByRole('button', { name: 'Đăng xuất' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Mở menu quản trị' })).toBeHidden()
    expect(health.cspViolations).toEqual([])
  })

  test('900 px: an icon rail whose links still have names, and still can be used', async ({ page }) => {
    await page.setViewportSize({ width: 900, height: 800 })
    await signedInAs(page, ['REPORT_VIEW'], ['Organizer'])
    await page.goto(REPORT)
    const aside = page.getByRole('complementary', { name: 'Thanh bên quản trị' })
    await expect(aside).toBeVisible()
    expect((await aside.boundingBox())?.width ?? 0).toBeLessThanOrEqual(72)
    // The text is `sr-only`, not display:none, so the icon link keeps its accessible name.
    const home = aside.getByRole('link', { name: 'Về trang sinh viên' })
    await expect(home).toBeVisible()
    expect((await home.boundingBox())?.width ?? 0).toBeLessThan(72)
    await expect(aside.getByRole('button', { name: 'Đăng xuất' })).toBeVisible()
    for (const name of ['Sáng', 'Tối', 'Theo hệ thống']) await expect(aside.getByRole('button', { name })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Mở menu quản trị' })).toBeHidden()
  })

  test('360 px: no sidebar; a top bar whose menu opens the same links in a sheet', async ({ page }) => {
    await page.setViewportSize({ width: 360, height: 740 })
    await signedInAs(page, ['REPORT_VIEW'], ['Organizer'])
    await page.goto(REPORT)
    await expect(page.getByRole('complementary', { name: 'Thanh bên quản trị' })).toBeHidden()
    await page.getByRole('button', { name: 'Mở menu quản trị' }).click()
    const sheet = page.getByRole('dialog', { name: 'Quản trị' })
    await expect(sheet.getByRole('link', { name: 'Về trang sinh viên' })).toBeVisible()
    await expect(sheet.getByRole('button', { name: 'Đăng xuất' })).toBeVisible()
  })

  test('a student who may not enter sees the refusal and a way out, not an admin frame', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 800 })
    await signedInAs(page, ['REGISTRATION_CREATE'])
    await page.goto(REPORT)
    await expect(page.getByRole('alert')).toContainText('Bạn không có quyền truy cập trang này.')
    await expect(page.getByRole('link', { name: 'Về trang chủ' })).toBeVisible()
    await expect(page.getByRole('complementary', { name: 'Thanh bên quản trị' })).toHaveCount(0)
  })
})
