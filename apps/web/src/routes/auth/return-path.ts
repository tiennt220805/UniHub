const DEFAULT_PATH = '/workshops'

/**
 * Where to go after signing in: the page a guard bounced the user from, if it is an in-app path.
 * Anything else — an absolute URL, a protocol-relative `//host`, the sign-in page itself — is
 * ignored, so a crafted link cannot turn sign-in into an open redirect.
 */
export function safeReturnPath(state: unknown): string {
  const from = (state as { from?: { pathname?: unknown; search?: unknown } } | null)?.from
  const pathname = from?.pathname
  if (
    typeof pathname !== 'string' ||
    !pathname.startsWith('/') ||
    pathname.startsWith('//') ||
    pathname.startsWith('/\\') ||
    pathname === '/login'
  ) {
    return DEFAULT_PATH
  }
  return pathname + (typeof from?.search === 'string' ? from.search : '')
}
