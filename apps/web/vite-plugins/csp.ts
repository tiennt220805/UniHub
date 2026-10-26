import type { Plugin } from 'vite'

/**
 * The Content-Security-Policy of the production build (web-coding-standards.md §11, checklist D14,
 * D18). The session token lives in sessionStorage, so a strict policy is a precondition of keeping
 * it there: script and style come from this origin only, nothing is framed in or posted out of
 * here, and the only network target besides this origin is the Backend API.
 *
 * `script-src` never gets an exception. No Stripe.js is loaded (checkout uses test tokens), so no
 * third-party origin is allowed. `frame-ancestors` is not here on purpose: browsers ignore it in a
 * `<meta>` tag, and it belongs to the HTTP header Phase 7 adds at the serving layer.
 */
export function buildCsp(apiBaseUrl: string): string {
  const connectSrc = apiBaseUrl ? ["'self'", new URL(apiBaseUrl).origin] : ["'self'"]
  return [
    "default-src 'self'",
    "script-src 'self'",
    "style-src 'self'",
    "img-src 'self' data:",
    "font-src 'self'",
    `connect-src ${connectSrc.join(' ')}`,
    "object-src 'none'",
    "base-uri 'self'",
    "form-action 'self'",
  ].join('; ')
}

/**
 * Injects the policy as a `<meta http-equiv>` tag, first in `<head>`, **only into a build**: the dev
 * server's hot reload needs inline scripts and a WebSocket that the policy would block.
 */
export function cspMeta(apiBaseUrl: string): Plugin {
  const content = buildCsp(apiBaseUrl)
  return {
    name: 'unihub-csp-meta',
    apply: 'build',
    transformIndexHtml() {
      return [
        {
          tag: 'meta',
          attrs: { 'http-equiv': 'Content-Security-Policy', content },
          injectTo: 'head-prepend',
        },
      ]
    },
  }
}
