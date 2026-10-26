import { isLive, type Session } from '../model/session'
import { useSessionStore } from './session-store'

/** The live session, or null when there is none or its expiry has passed. */
export function currentSession(now: number = Date.now()): Session | null {
  const { session } = useSessionStore.getState()
  return session && isLive(session, now) ? session : null
}

/**
 * The token to send, or null. An expired token is never sent: the Backend API answers 401 to a bad
 * bearer token even on a public path, so a stale token would turn a sign-in or a catalog read into
 * a spurious 401.
 */
export const currentAccessToken = (): string | null => currentSession()?.accessToken ?? null

export const startSession = (session: Session) => useSessionStore.getState().start(session)

export const endSession = () => useSessionStore.getState().end()
