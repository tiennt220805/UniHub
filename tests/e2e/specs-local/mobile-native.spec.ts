
import { test, expect } from '../support/local-fixtures'
import type { PageHealth } from '../support/page-health'

// Phase 8, task 7.3 (the `mobile-native` pass), on the three engines at 360 px. What can be shown without a phone is shown
// here: the rules are in the built CSS and reach the elements. What cannot — a stuck hover, the tap flash, the keyboard, the URL
// bar — needs a real device, and the checklist says so. Evidence for M-86 (the keyboard and touch half).

test.use({ viewport: { width: 360, height: 740 }, hasTouch: true, isMobile: false })

test('a form control is at least 16 px, so iOS does not zoom the page on focus', async ({ page, health }) => {
  await page.goto('/login')
  for (const name of ['Email', 'Mật khẩu']) {
    const size = await page.getByRole('textbox', { name }).or(page.getByLabel(name, { exact: true })).first().evaluate((el) => parseFloat(getComputedStyle(el).fontSize))
    expect(size, name).toBeGreaterThanOrEqual(16)
  }
  expect(health.cspViolations).toEqual([])
})

test('the page never disables zoom', async ({ page }) => {
  await page.goto('/login')
  const content = await page.locator('meta[name="viewport"]').getAttribute('content')
  expect(content).not.toMatch(/user-scalable|maximum-scale/)
})

test('buttons and links fire at once, and a button label is not selectable', async ({ page }) => {
  await page.goto('/login')
  const button = page.getByRole('button', { name: 'Đăng nhập' })
  const style = await button.evaluate((el) => {
    const computed = getComputedStyle(el)
    return { touchAction: computed.touchAction, userSelect: computed.userSelect || computed.getPropertyValue('-webkit-user-select') }
  })
  expect(style.touchAction).toBe('manipulation')
  expect(style.userSelect).toBe('none')
  // Content stays selectable: the heading is text, not a control.
  const heading = await page.getByRole('heading', { level: 1 }).evaluate((el) => getComputedStyle(el).userSelect || getComputedStyle(el).getPropertyValue('-webkit-user-select'))
  expect(heading).not.toBe('none')
  const link = await page.getByRole('link', { name: 'UniHub' }).first().evaluate((el) => getComputedStyle(el).touchAction)
  expect(link).toBe('manipulation')
})

test('no hover style applies on a touch-only device', async ({ browser }) => {
  // A context that cannot hover: Tailwind compiles `hover:` under @media (hover: hover), so a tap leaves nothing stuck.
  const context = await browser.newContext({ viewport: { width: 360, height: 740 }, hasTouch: true, isMobile: true })
  const page = await context.newPage()
  await page.goto('/login')
  const canHover = await page.evaluate(() => window.matchMedia('(hover: hover)').matches)
  if (canHover) test.skip(true, 'this engine reports hover even in a mobile context; the rule is checked on the others')
  // Walk into @layer and @media blocks: a style rule that names :hover must have a (hover: hover) media rule above it.
  const rules = await page.evaluate(() => {
    const stray: string[] = []
    let gatedCount = 0
    const walk = (list: CSSRuleList, gated: boolean) => {
      for (const rule of Array.from(list)) {
        if (rule instanceof CSSStyleRule) {
          if (rule.selectorText.includes(':hover')) {
            if (gated) gatedCount += 1
            else stray.push(rule.selectorText.slice(0, 80))
          }
          if (rule.cssRules?.length) walk(rule.cssRules, gated)
        } else if (rule instanceof CSSMediaRule) {
          walk(rule.cssRules, gated || /hover:\s*hover/.test(rule.conditionText))
        } else if ('cssRules' in rule) {
          walk((rule as CSSGroupingRule).cssRules, gated)
        }
      }
    }
    for (const sheet of Array.from(document.styleSheets)) walk(sheet.cssRules, false)
    return gatedCount > 0 ? stray : ['the walk found no :hover rule at all, so the check proves nothing']
  })
  expect(rules).toEqual([]) // every :hover sits inside a media rule
  await context.close()
})

test('the browser bar follows a choice that differs from the system', async ({ page }) => {
  await page.emulateMedia({ colorScheme: 'light' })
  await page.goto('/login')
  // On a phone the theme switch lives in the menu sheet.
  await page.getByRole('button', { name: 'Mở menu' }).click()
  const bars = () => page.locator('meta[name="theme-color"]').evaluateAll((metas) => metas.map((meta) => meta.getAttribute('content')))
  expect(await bars()).toEqual(['#FFFFFF', '#FFFFFF'])
  await page.getByRole('button', { name: 'Tối' }).click()
  expect(await bars()).toEqual(['#111B15', '#111B15'])
  await page.getByRole('button', { name: 'Sáng' }).click()
  expect(await bars()).toEqual(['#FFFFFF', '#FFFFFF'])
})
