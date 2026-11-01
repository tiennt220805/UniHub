import { ApiError, type Api } from '@/shared/api/api-client'
import type { SecretStore } from '@/shared/secure/secret-store'

import { refreshVerificationKey } from './verification-key'

const SESSION_STORAGE = 'unihub.session'
/** The permission that makes an account Check-in Staff. Authorization asks for this, never for a role name. */
export const CHECKIN_SCAN = 'CHECKIN_SCAN'

export type Session = {
  readonly accessToken: string
  /** Epoch milliseconds. There is no refresh token: at this time the person signs in again. */
  readonly expiresAt: number
  readonly user: { readonly id: string; readonly fullName: string; readonly email: string }
  readonly permissions: readonly string[]
}

export type SignInResult =
  | { kind: 'SIGNED_IN'; session: Session; hasKey: boolean }
  | { kind: 'BAD_CREDENTIALS' }
  /** The account signed in but is not Check-in Staff: nothing is stored. */
  | { kind: 'NOT_STAFF' }
  | { kind: 'UNAVAILABLE' }

type SignInDeps = {
  /** An API client that sends this bearer token (or none): the key endpoint needs the new session's. */
  apiFor: (token: string | null) => Api
  secrets: SecretStore
  now: () => number
}

/**
 * Signs a Check-in Staff member in and caches the verification key. A Student's account signs in
 * at the API but is refused here: the key endpoint answers `403`, and the login response already
 * shows no `CHECKIN_SCAN`, so nothing is stored for them.
 */
export async function signIn(
  { apiFor, secrets, now }: SignInDeps,
  email: string,
  password: string,
  cachedKey: boolean,
): Promise<SignInResult> {
  let login
  try {
    login = await apiFor(null).login(email, password)
  } catch (error) {
    if (error instanceof ApiError && (error.status === 401 || error.status === 400)) {
      return { kind: 'BAD_CREDENTIALS' }
    }
    return { kind: 'UNAVAILABLE' }
  }
  if (!login.permissions.includes(CHECKIN_SCAN)) return { kind: 'NOT_STAFF' }

  const session: Session = {
    accessToken: login.accessToken,
    expiresAt: now() + login.expiresIn * 1000,
    user: { id: login.user.id, fullName: login.user.fullName, email: login.user.email },
    permissions: login.permissions,
  }
  const refreshed = await refreshVerificationKey(apiFor(session.accessToken), secrets)
  if (refreshed.outcome === 'NOT_PERMITTED') return { kind: 'NOT_STAFF' }
  await secrets.set(SESSION_STORAGE, JSON.stringify(session))
  return { kind: 'SIGNED_IN', session, hasKey: refreshed.outcome === 'UPDATED' || cachedKey }
}

/**
 * The stored session, **whether or not its token has expired.** The token lives 30 minutes and there
 * is no refresh (`auth-and-identity.md` §4), but scanning is offline and must go on through an event
 * longer than that, including after the app was closed and opened again. Expiry only means the Backend
 * API will refuse the next sync with a `401`; that is what ends the session, and it keeps every scan
 * (`checkin.md` §7).
 */
export async function restoreSession(secrets: SecretStore): Promise<Session | null> {
  const stored = await secrets.get(SESSION_STORAGE)
  if (stored === null) return null
  try {
    const session = JSON.parse(stored) as Session
    if (
      typeof session.accessToken === 'string' &&
      typeof session.expiresAt === 'number' &&
      typeof session.user?.id === 'string' &&
      Array.isArray(session.permissions)
    ) {
      return session
    }
  } catch {
    // an unreadable entry is no session
  }
  await secrets.remove(SESSION_STORAGE)
  return null
}

/** Ends the session. Queued scans, the verification key and the device id are deliberately left alone. */
export async function endSession(secrets: SecretStore): Promise<void> {
  await secrets.remove(SESSION_STORAGE)
}
