import type { Page, TestInfo } from '@playwright/test'

/**
 * A full-page screenshot for a person to look at (`test-results/*-screens/`), on the engines where taking one does not break
 * the CSP guard. Playwright injects a `<style>` into the page for a screenshot, and WebKit reports that as a violation of the
 * page's policy ("style-src-elem blocked inline", no source file); the guard of `local-fixtures.ts` has no allowance for it, so
 * WebKit takes none (Phase 8 task 8.4: a guard that drops reports during a screenshot could drop a real one of the same form).
 * The shots of the other two engines show the same pages.
 */
export async function shot(page: Page, testInfo: TestInfo, path: string): Promise<void> {
  if (testInfo.project.name === 'webkit') {
    testInfo.annotations.push({ type: 'screenshot skipped', description: 'WebKit reports the style Playwright injects for a screenshot as a CSP violation' })
    return
  }
  await page.screenshot({ path, fullPage: true })
}
