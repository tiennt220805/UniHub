import { env } from '@/shared/config/env'
import { AppError, toAppError } from './errors'

type HttpClientHooks = {
  getAccessToken: () => string | null
  onUnauthorized: () => void
}

// No session exists until the sign-in UI lands, so the defaults send no token and ignore 401s.
let hooks: HttpClientHooks = { getAccessToken: () => null, onUnauthorized: () => {} }

/** Called once by app/ at startup; shared/ never imports the session from features/auth. */
export function configureHttpClient(next: HttpClientHooks): void {
  hooks = next
}

/** The single way to call the Backend API (web-coding-standards.md §6). */
export async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json, application/problem+json')
  const token = hooks.getAccessToken()
  if (token) headers.set('Authorization', `Bearer ${token}`)

  let response: Response
  try {
    response = await fetch(`${env.apiBaseUrl}${path}`, { ...init, headers })
  } catch (cause) {
    throw new AppError(null, null, null, { cause })
  }
  if (!response.ok) {
    const error = await toAppError(response)
    if (response.status === 401 && token) hooks.onUnauthorized() // login's own 401 never ends a session
    throw error
  }
  return (response.status === 204 ? undefined : await response.json()) as T
}
