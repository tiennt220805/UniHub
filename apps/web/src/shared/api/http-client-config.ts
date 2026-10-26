export type HttpClientHooks = {
  getAccessToken: () => string | null
  onUnauthorized: () => void
}

// No session until the sign-in UI wires one in, so the defaults send no token and ignore 401s.
let hooks: HttpClientHooks = { getAccessToken: () => null, onUnauthorized: () => {} }

/**
 * Called once by app/ at startup; shared/ never imports the session from features/auth. Kept apart
 * from http-client.ts because the lint rule that only features/<name>/api/ may import the client
 * (web-coding-standards.md §2 rule 6) must not also bar app/ from wiring it.
 */
export function configureHttpClient(next: HttpClientHooks): void {
  hooks = next
}

export const currentHooks = (): HttpClientHooks => hooks
