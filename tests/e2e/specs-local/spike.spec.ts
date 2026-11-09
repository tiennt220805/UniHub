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

test('AlertDialog opens, locks the page scroll, is styled and both buttons close it', async ({ page, health }) => {
  await open(page, 'alertdialog')
  await page.getByRole('button', { name: 'Hủy workshop' }).click()
  const dialog = page.getByRole('alertdialog', { name: 'Xác nhận hủy thử' })
  await expect(dialog).toBeVisible()
  expect(await css(dialog, 'position')).toBe('fixed')
  expect(await css(dialog, 'background-color')).not.toMatch(transparent)
  expect(await bodyOverflow(page)).toBe('hidden')
  await dialog.getByRole('button', { name: 'Giữ lại' }).click()
  await expect(dialog).toBeHidden()
  expect(await bodyOverflow(page)).toBe('')
  await page.getByRole('button', { name: 'Hủy workshop' }).click()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Hủy workshop' }).click()
  await expect(page.getByRole('alertdialog')).toBeHidden()
  expect(await violations(page, health)).toEqual([])
})

test('native date and datetime-local inputs take a value and raise nothing', async ({ page, health }, info) => {
  await open(page, 'date')
  const date = page.getByLabel('Từ ngày')
  const start = page.getByLabel('Bắt đầu')
  await expect(date).toHaveValue('2026-11-12')
  await date.fill('2026-12-01')
  await start.fill('2026-12-01T09:30')
  await expect(date).toHaveValue('2026-12-01')
  await expect(start).toHaveValue('2026-12-01T09:30')
  // Computed style proves the control is styled by our classes, not left raw.
  expect(await css(date, 'border-top-width')).toBe('1px')
  const seen = await violations(page, health)
  info.annotations.push({ type: 'date-input-reports', description: String(seen.length) })
  expect(seen).toEqual([])
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

test('our own toast shows, is styled and dismisses, with its two live regions in place from the start', async ({ page, health }) => {
  await open(page, 'toast')
  // Both regions exist before anything is shown, or a screen reader announces nothing.
  await expect(page.locator('[data-slot="toast-region-polite"]')).toHaveCount(1)
  await expect(page.locator('[data-slot="toast-region-assertive"]')).toHaveCount(1)
  await page.getByRole('button', { name: 'Hiện thông báo' }).click()
  const ok = page.locator('[data-slot="toast-region-polite"]').getByText('Đã lưu thay đổi.')
  await expect(ok).toBeVisible()
  const item = page.locator('[data-slot="toast"]').filter({ hasText: 'Đã lưu thay đổi.' })
  // The things Sonner lost under the policy: a fixed container, a surface colour and a border, all from our own classes.
  expect(await css(page.locator('[data-slot="toast"]').first().locator('xpath=../..'), 'position')).toBe('fixed')
  expect(await css(item, 'background-color')).not.toMatch(transparent)
  expect(await css(item, 'border-top-width')).toBe('1px')
  await page.getByRole('button', { name: 'Hiện lỗi' }).click()
  await expect(page.locator('[data-slot="toast-region-assertive"]').getByText('Không lưu được.')).toBeVisible()
  await page.getByRole('button', { name: 'Đóng thông báo' }).last().click()
  await expect(page.getByText('Không lưu được.')).toHaveCount(0)
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

// Task 4.4 (D144): the generated cover is SVG with presentation attributes only, and the seat bar sets its width through
// the CSSOM. Both must survive `style-src 'self'` on all three engines, and be seen to be drawn, not just silent.
test('the workshop card draws its generated cover and seat bar under the policy, with every family of cover', async ({ page, health }) => {
  await open(page, 'cards')
  const cards = page.locator('[data-slot="card"]')
  await expect(cards).toHaveCount(12)
  const covers = page.locator('[data-slot="workshop-cover"] svg')
  await expect(covers).toHaveCount(12)
  for (const box of await covers.evaluateAll((svgs) => svgs.map((svg) => svg.getBoundingClientRect().width))) {
    expect(box).toBeGreaterThan(150)
  }
  // The shapes carry their colours as attributes: a drawn cover has painted (non-"none") fills.
  const fills = await covers.evaluateAll((svgs) => svgs.map((svg) => getComputedStyle(svg.querySelector('rect')!).fill))
  for (const fill of fills) expect(fill).not.toMatch(/none|rgba\(0, 0, 0, 0\)/)
  // No <style> element and no style="" attribute inside any cover: the two things the policy refuses.
  expect(await page.locator('[data-slot="workshop-cover"] svg style, [data-slot="workshop-cover"] svg [style]').count()).toBe(0)
  // All three families are drawn over the twelve ids.
  const groups = await covers.evaluateAll((svgs) =>
    svgs.map((svg) => (svg.querySelector('circle') ? 'sun' : svg.querySelectorAll('rect').length > 4 ? 'tiles' : 'bars')),
  )
  expect(new Set(groups)).toEqual(new Set(['sun', 'bars', 'tiles']))
  // The seat bar's width is set from script (the CSSOM, which the policy allows) and is really applied.
  for (const [level, expected] of [['ok', 10 / 60], ['low', 55 / 60], ['full', 1]] as const) {
    const meter = page.locator(`[data-level="${level}"]`).first()
    await expect(meter).toBeVisible()
    const ratio = await meter.evaluate((element) => {
      const fill = element.querySelector('[aria-hidden="true"] > div') as HTMLElement
      return fill.getBoundingClientRect().width / (fill.parentElement as HTMLElement).getBoundingClientRect().width
    })
    expect(ratio).toBeGreaterThan(expected - 0.02)
    expect(ratio).toBeLessThan(expected + 0.02)
  }
  expect(await violations(page, health)).toEqual([])
})
