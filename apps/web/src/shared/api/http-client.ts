import { env } from '@/shared/config/env'
import { AppError, toAppError } from './errors'
import { currentHooks } from './http-client-config'

// Re-exported so the client and its configuration read as one API to the code that may use both.
export { configureHttpClient } from './http-client-config'

/** The single way to call the Backend API (web-coding-standards.md §6). */
export async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json, application/problem+json')
  const hooks = currentHooks()
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
