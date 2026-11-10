import type { Page } from '@playwright/test'

import { test, expect } from '../support/local-fixtures'
import type { PageHealth } from '../support/page-health'

// Task 2.4 (Phase 8 checklist Q8, D124): the colour theme is on <html> before the first paint, on the three engines.
// A flash shows up as the page being built without the class and the script adding it later, so each test asks the
// question at the moment <body> is created: that is after the blocking script in <head> and before anything can paint.
// Evidence for M-86.

declare global {
  interface Window {
    __darkWhenBodyCreated?: boolean
  }
}

/** Records whether <html> was already dark when the parser created <body>. Runs before the page's own scripts. */
async function recordAtBodyCreation(page: Page) {
  await page.addInitScript(() => {
    const observer = new MutationObserver(() => {
      if (document.body) {
        window.__darkWhenBodyCreated = document.documentElement.classList.contains('dark')
        observer.disconnect()
      }
    })
    // `document`, not `document.documentElement`: the init script can run before the root element exists.
    observer.observe(document, { childList: true, subtree: true })
  })
}

const darkAtBody = (page: Page) => page.evaluate(() => window.__darkWhenBodyCreated)

test.describe('operating system in dark mode, nothing chosen yet', () => {
  test.use({ colorScheme: 'dark' })

  test('the first paint is already dark', async ({ page, health }) => {
    await recordAtBodyCreation(page)
    await page.goto('/login')
    expect(await darkAtBody(page)).toBe(true)
    expect(health.cspViolations).toEqual([])
    expect(await page.evaluate(() => window.__cspViolations ?? [])).toEqual([])
  })

  test('the control: with the init script blocked the page is built light, so the check above can fail', async ({ page }) => {
    await page.route('**/theme-init.js', (route) => route.fulfill({ contentType: 'text/javascript', body: '' }))
    await recordAtBodyCreation(page)
    await page.goto('/login')
    expect(await darkAtBody(page)).toBe(false)
  })

  test('a stored `light` beats the system', async ({ page }) => {
    await page.addInitScript(() => window.localStorage.setItem('unihub.theme', 'light'))
    await recordAtBodyCreation(page)
    await page.goto('/login')
    expect(await darkAtBody(page)).toBe(false)
  })
})

test.describe('operating system in light mode', () => {
  test.use({ colorScheme: 'light' })

  test('a stored `dark` beats the system', async ({ page }) => {
    await page.addInitScript(() => window.localStorage.setItem('unihub.theme', 'dark'))
    await recordAtBodyCreation(page)
    await page.goto('/login')
    expect(await darkAtBody(page)).toBe(true)
  })

  test('choosing dark with the toggle applies at once and survives a reload without a flash', async ({ page }) => {
    await page.goto('/login')
    await expect(page.locator('html')).not.toHaveClass(/dark/)
    await page.getByRole('button', { name: 'Tối' }).click()
    await expect(page.locator('html')).toHaveClass(/dark/)
    expect(await page.evaluate(() => window.localStorage.getItem('unihub.theme'))).toBe('dark')

    await recordAtBodyCreation(page)
    await page.reload()
    expect(await darkAtBody(page)).toBe(true)
    await expect(page.getByRole('button', { name: 'Tối' })).toHaveAttribute('aria-pressed', 'true')
  })

  test('the page follows the system when it changes while the choice is `system`', async ({ page }) => {
    await page.goto('/login')
    await expect(page.getByRole('button', { name: 'Theo hệ thống' })).toHaveAttribute('aria-pressed', 'true')
    await page.emulateMedia({ colorScheme: 'dark' })
    await expect(page.locator('html')).toHaveClass(/dark/)
    await page.emulateMedia({ colorScheme: 'light' })
    await expect(page.locator('html')).not.toHaveClass(/dark/)
  })

  test('the page uses real colours: dark paints the dark background, light the light one', async ({ page }) => {
    await page.goto('/login')
    const lightBg = await page.evaluate(() => getComputedStyle(document.body).backgroundColor)
    await page.getByRole('button', { name: 'Tối' }).click()
    const darkBg = await page.evaluate(() => getComputedStyle(document.body).backgroundColor)
    expect(lightBg).toBe('rgb(250, 250, 247)')
    expect(darkBg).toBe('rgb(10, 18, 13)')
  })
})
