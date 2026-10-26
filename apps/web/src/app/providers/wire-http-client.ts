import type { QueryClient } from '@tanstack/react-query'

import { currentAccessToken, endSession } from '@/features/auth'
import { configureHttpClient } from '@/shared/api/http-client-config'

/**
 * Connects the http client to the session (web-coding-standards.md §6): `shared/` cannot import
 * `features/auth`, so `app/` hands it the two callbacks. A 401 on a request that carried a token
 * ends the session and drops every cached read; the route guards then send the user to sign-in.
 */
export function wireHttpClient(queryClient: QueryClient): void {
  configureHttpClient({
    getAccessToken: currentAccessToken,
    onUnauthorized: () => {
      endSession()
      queryClient.clear()
    },
  })
}
