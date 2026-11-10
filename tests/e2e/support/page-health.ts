import type { Page } from '@playwright/test'

// What a page reported about itself while a test ran. A Content-Security-Policy violation is a failure of the
// test that caused it (AC85.2, test-matrix.md §4.5: the repeatable browser CSP check), however green the rest is.
export type PageHealth = { cspViolations: string[]; consoleErrors: string[] }

declare global {
  interface Window {
    __cspViolations?: string[]
    __selectsCreated?: number
  }
}

export async function watch(page: Page): Promise<PageHealth> {
  const health: PageHealth = { cspViolations: [], consoleErrors: [] }
  // The event fires in the page; the init script runs before the page's own scripts, in every frame.
  await page.addInitScript(() => {
    window.__cspViolations = []
    window.__selectsCreated = 0
    // Counts the native <select> elements that get into the page (see the WebKit exception in `pageHealth`). A
    // MutationObserver, not a wrapper around createElement: a wrapper would stand in the call stack and WebKit
    // attributes the report to the caller, so the report would point at this script instead of the application's.
    new MutationObserver((records) => {
      for (const record of records) {
        for (const node of Array.from(record.addedNodes)) {
          if (!(node instanceof Element)) continue
          window.__selectsCreated = (window.__selectsCreated ?? 0) + (node.matches('select') ? 1 : 0) + node.querySelectorAll('select').length
        }
      }
    }).observe(document, { childList: true, subtree: true })
    document.addEventListener('securitypolicyviolation', (event) => {
      window.__cspViolations?.push(
        `${event.violatedDirective} blocked ${event.blockedURI || 'inline'} on ${document.location.pathname} at ${event.sourceFile}:${event.lineNumber}:${event.columnNumber} [${(event.sample || '').slice(0, 80)}]`,
      )
    })
  })
  page.on('console', (message) => {
    if (message.type() === 'error') health.consoleErrors.push(message.text())
  })
  return health
}

// The one tolerated exception, decided by the owner 2026-11-06 (phase-7-cluster-checks.md, 9.4): WebKit reports a
// `style-src-attr` violation with an empty sample whenever a page creates a native <select>, with the strict CSP and no
// application code involved (reproduced on a page of its own). It is the checkout's test-token dropdown, shown only in a
// test-mode build. At most two reports per <select> that the page put in the DOM are tolerated (that is what WebKit sends);
// a third, anything else, on any engine, or this on any other page, still fails. The report is pinned to the page, the origin
// under test (https on the cluster; a run on the local stack is http, and the report names that scheme) and an empty sample.
function webkitSelectQuirk(origin: string): RegExp {
  const escaped = origin.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return new RegExp(`^style-src-attr blocked inline on /me/checkout/[^ ]+ at ${escaped}/assets/[^ ]+\\.js:\\d+:\\d+ \\[\\]$`)
}

/**
 * What of a test's CSP reports and console errors is a failure. Console errors other than CSP ones are not: a 4xx a journey
 * provokes on purpose logs "Failed to load resource". A CSP error text in the console counts as a violation too.
 */
export function settleCsp(input: { engine: string; origin: string; reports: string[]; consoleErrors: string[]; selects: number }) {
  const cspInConsole = input.consoleErrors.filter((text) => /content security policy|violates the following/i.test(text))
  const quirk = webkitSelectQuirk(input.origin)
  const isQuirk = (entry: string) => input.engine === 'webkit' && quirk.test(entry)
  const candidates = input.reports.filter(isQuirk)
  const tolerated = candidates.slice(0, input.selects * 2)
  const violations = [...input.reports.filter((entry) => !isQuirk(entry)), ...candidates.slice(tolerated.length)]
  // Each tolerated event is followed by the same message in the console; take out that many, not all of them.
  const consoleLeft = [...cspInConsole]
  for (let i = 0; i < tolerated.length; i++) {
    const at = consoleLeft.findIndex((text) => /^Refused to apply a stylesheet because its hash/.test(text))
    if (at >= 0) consoleLeft.splice(at, 1)
  }
  return { failures: [...violations, ...consoleLeft], tolerated }
}
