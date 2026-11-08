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
