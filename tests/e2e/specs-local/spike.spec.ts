import { expect, test as base, type Locator, type Page } from '@playwright/test'

import { watch, type PageHealth } from '../support/page-health'

// Task 2.3 (Phase 8 checklist, D125, Q9): each UI library the design leans on is opened under the production
// Content-Security-Policy on the three engines. "No violation" proves nothing for a component that never opened, so
// every test also checks that the component appeared and carries its styles. Each library gets a page of its own
// (`/__spike?only=…`) so a violation is pinned on the library that caused it.
//
// Rejected before this file was written, each with the same evidence on all three engines (checklist 2.3):
//   Sonner      its module injects two <style> elements at import time; the toaster stays `position: static`.
//   NumberFlow  injects a <style> when the page loads; the digit columns render unstyled ("0123456789…").
//   Select      Radix's SelectViewport always renders a <style> element (node_modules/@radix-ui/react-select, the
//               `[data-radix-select-viewport]{scrollbar-width:none…}` rule); the policy refuses it on all three engines
//               each time a list opens. Allowing it would mean a hash in `style-src`: the owner's call, not this file's.
// Sonner and NumberFlow were removed from package.json and Select from shared/ui, so they cannot be tested here any
// more; the evidence is in checklists/phase-8-web-ui.md, 2.3.

const test = base.extend<{ health: PageHealth }>({
  health: async ({ page }, use) => {
    await use(await watch(page))
  },
})

/** Violations the page reported: the `securitypolicyviolation` events and CSP text in the console. */
async function violations(page: Page, health: PageHealth): Promise<string[]> {
  const events = await page.evaluate(() => window.__cspViolations ?? [])
  const inConsole = health.consoleErrors.filter((text) => /content security policy|violates the following|refused to/i.test(text))
  return [...events, ...inConsole]
}

async function css(locator: Locator, property: string): Promise<string> {
  return locator.evaluate((element, name) => getComputedStyle(element).getPropertyValue(name), property)
}

const open = async (page: Page, only: string) => {
  await page.goto(`/__spike?only=${only}`)
  await expect(page.getByRole('heading', { name: 'Kiểm tra CSP của thư viện giao diện' })).toBeVisible()
}

const transparent = /^rgba\(0, 0, 0, 0\)$|^transparent$/
const bodyOverflow = (page: Page) => page.evaluate(() => document.body.style.overflow)

test('the production build really has a Content-Security-Policy (the control: without it every result is a false pass)', async ({ page }) => {
  await open(page, 'tabs')
  const policy = await page.locator('meta[http-equiv="Content-Security-Policy"]').getAttribute('content')
  expect(policy).toContain("style-src 'self'")
  expect(policy).not.toContain('unsafe-inline')
  // And it bites: an inline <style> is refused, which is what a library that injects CSS at run time would hit.
  const blocked = await page.evaluate(
    () =>
      new Promise<string>((resolve) => {
        document.addEventListener('securitypolicyviolation', (event) => resolve(event.violatedDirective), { once: true })
        const style = document.createElement('style')
        style.textContent = 'body { outline: 1px solid red }'
        document.head.appendChild(style)
        setTimeout(() => resolve('not blocked'), 1000)
      }),
  )
  expect(blocked).toMatch(/^style-src/)
})

test('Dialog opens, locks the page scroll, is styled and closes', async ({ page, health }) => {
  await open(page, 'dialog')
  await page.getByRole('button', { name: 'Mở hộp thoại' }).click()
  const dialog = page.getByRole('dialog', { name: 'Hộp thoại thử' })
  await expect(dialog).toBeVisible()
  expect(await css(dialog, 'position')).toBe('fixed')
  expect(await css(dialog, 'background-color')).not.toMatch(transparent)
  // The scroll lock is the part of Radix that needs a stylesheet in its stock form (csp-safe-remove-scroll.tsx).
  expect(await bodyOverflow(page)).toBe('hidden')
  await page.keyboard.press('Escape')
  await expect(dialog).toBeHidden()
  expect(await bodyOverflow(page)).toBe('')
  expect(await violations(page, health)).toEqual([])
})

test('Popover opens and is styled', async ({ page, health }) => {
  await open(page, 'popover')
  await page.getByRole('button', { name: 'Mở popover' }).click()
  const content = page.getByText('Nội dung popover')
  await expect(content).toBeVisible()
  expect(await css(content, 'background-color')).not.toMatch(transparent)
  expect(await violations(page, health)).toEqual([])
})

test('DropdownMenu opens, picks and is styled', async ({ page, health }) => {
  await open(page, 'menu')
  await page.getByRole('button', { name: 'Mở menu' }).click()
  const menu = page.getByRole('menu')
  await expect(menu).toBeVisible()
  expect(await css(menu, 'background-color')).not.toMatch(transparent)
  await page.getByRole('menuitem', { name: 'Mục hai' }).click()
  await expect(menu).toBeHidden()
  expect(await violations(page, health)).toEqual([])
})

test('Sheet opens, locks the page scroll, is styled and closes', async ({ page, health }) => {
  await open(page, 'sheet')
  await page.getByRole('button', { name: 'Mở ngăn bên' }).click()
  const sheet = page.getByRole('dialog', { name: 'Ngăn bên thử' })
  await expect(sheet).toBeVisible()
  expect(await css(sheet, 'position')).toBe('fixed')
  expect(await bodyOverflow(page)).toBe('hidden')
  await page.keyboard.press('Escape')
  await expect(sheet).toBeHidden()
  expect(await bodyOverflow(page)).toBe('')
  expect(await violations(page, health)).toEqual([])
})

test('Tabs switch', async ({ page, health }) => {
  await open(page, 'tabs')
  await page.getByRole('tab', { name: 'Tab hai' }).click()
  await expect(page.getByRole('tab', { name: 'Tab hai' })).toHaveAttribute('aria-selected', 'true')
  await expect(page.getByText('Nội dung tab hai')).toBeVisible()
  expect(await violations(page, health)).toEqual([])
})

test('recharts draws a bar chart and a donut', async ({ page, health }) => {
  await open(page, 'charts')
  const bars = page.getByRole('img', { name: 'Biểu đồ cột' })
  await expect(bars.locator('.recharts-bar-rectangle')).toHaveCount(4)
  const donut = page.getByRole('img', { name: 'Biểu đồ vòng' })
  await expect(donut.locator('.recharts-pie-sector')).toHaveCount(2)
  const box = await bars.boundingBox()
  expect(box?.width ?? 0).toBeGreaterThan(50)
  expect(await violations(page, health)).toEqual([])
})

test('the whole page together raises nothing at load', async ({ page, health }) => {
  await page.goto('/__spike')
  await expect(page.getByRole('heading', { name: 'Kiểm tra CSP của thư viện giao diện' })).toBeVisible()
  await page.waitForTimeout(500)
  expect(await violations(page, health)).toEqual([])
})
