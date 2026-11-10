import type { Page } from '@playwright/test'

import { organizer } from '../support/accounts'
import { expect, signInThroughTheForm, test } from '../support/fixtures'

// M-86, the toggle (Phase 8 task 8.3): the colour theme is chosen with the buttons in the header, applies at once, is kept in
// the browser, and the same choice holds in the student pages and in the administration frame. The first paint and the colours
// of both themes are specs-local/theme.spec.ts; the contrast and the keyboard are specs-local/a11y.spec.ts; the `theme-init.js`
// the first paint depends on being served as JavaScript is transport.spec.ts. This one is the toggle on the deployed origin.
const html = (page: Page) => page.locator('html')
const background = (page: Page) => page.evaluate(() => getComputedStyle(document.body).backgroundColor)

test.use({ colorScheme: 'light' })

test('the header toggle changes the page at once, and the choice survives a reload', async ({ page }) => {
  await page.goto('/login')
  await expect(page.getByRole('button', { name: 'Theo hệ thống' })).toHaveAttribute('aria-pressed', 'true')
  await expect(html(page)).not.toHaveClass(/dark/)
  const light = await background(page)

  await page.getByRole('button', { name: 'Tối' }).click()
  await expect(html(page)).toHaveClass(/dark/)
  expect(await background(page)).not.toBe(light)
  expect(await page.evaluate(() => window.localStorage.getItem('unihub.theme'))).toBe('dark')

  await page.reload()
  await expect(html(page)).toHaveClass(/dark/)
  await expect(page.getByRole('button', { name: 'Tối' })).toHaveAttribute('aria-pressed', 'true')

  await page.getByRole('button', { name: 'Sáng' }).click()
  await expect(html(page)).not.toHaveClass(/dark/)
  expect(await background(page)).toBe(light)

  // "Theo hệ thống" follows the system again, in both directions.
  await page.getByRole('button', { name: 'Theo hệ thống' }).click()
  await page.emulateMedia({ colorScheme: 'dark' })
  await expect(html(page)).toHaveClass(/dark/)
  await page.emulateMedia({ colorScheme: 'light' })
  await expect(html(page)).not.toHaveClass(/dark/)
})

test('the choice made on the student pages holds in the administration frame, and its own toggle changes it back', async ({ page }) => {
  await page.goto('/login')
  await page.getByRole('button', { name: 'Tối' }).click()
  await signInThroughTheForm(page, organizer())
  await expect(html(page)).toHaveClass(/dark/)

  // Into the frame: still dark, and the toggle there says so.
  await page.goto('/admin/workshops')
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  await expect(html(page)).toHaveClass(/dark/)
  await expect(page.getByRole('button', { name: 'Tối' })).toHaveAttribute('aria-pressed', 'true')

  await page.getByRole('button', { name: 'Sáng' }).click()
  await expect(html(page)).not.toHaveClass(/dark/)
  await page.reload()
  await expect(html(page)).not.toHaveClass(/dark/)

  // And back on the student pages the choice made in the frame is the one in force.
  await page.goto('/workshops')
  await expect(html(page)).not.toHaveClass(/dark/)
  await expect(page.getByRole('button', { name: 'Sáng' })).toHaveAttribute('aria-pressed', 'true')
})
