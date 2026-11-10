import type { Page } from '@playwright/test'

import { test, expect } from '../support/local-fixtures'
import type { PageHealth } from '../support/page-health'

// Task 7.1 (Phase 8 checklist, D113, web-design-system.md §6 rule 7): with `prefers-reduced-motion: reduce` an overlay
// keeps its fade and loses its slide and zoom, on the three engines. The Vitest tests cannot see this (jsdom applies no
// CSS). The slide of the student menu sheet is read from the variable the `enter` keyframe takes its offset from, at the
// moment the sheet is open; the same rule in index.css covers dialog, alert dialog, popover and dropdown menu.

async function openMenuAndReadEnterOffset(page: Page) {
  await page.setViewportSize({ width: 360, height: 740 })
  await page.goto('/login')
  await page.getByRole('button', { name: 'Mở menu' }).click()
  const sheet = page.locator('[data-slot="sheet-content"]')
  await expect(sheet).toBeVisible()
  return sheet.evaluate((el) => getComputedStyle(el).getPropertyValue('--tw-enter-translate-x').trim())
}

test.describe('overlays and the reduced-motion preference', () => {
  test.use({ reducedMotion: 'no-preference' })

  test('control: with no preference the menu sheet slides in from the side, so the check below can fail', async ({ page }) => {
    const offset = await openMenuAndReadEnterOffset(page)
    expect(offset).not.toBe('')
    expect(offset).not.toBe('0')
  })
})

test.describe('reduced motion asked for', () => {
  test.use({ reducedMotion: 'reduce' })

  test('the menu sheet has no slide offset, and CSP reports stay at zero', async ({ page, health }) => {
    expect(await openMenuAndReadEnterOffset(page)).toBe('0')
    expect(health.cspViolations).toEqual([])
    expect(await page.evaluate(() => window.__cspViolations ?? [])).toEqual([])
  })
})
