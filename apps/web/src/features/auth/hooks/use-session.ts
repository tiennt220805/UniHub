import { isLive, type Session } from '../model/session'
import { useSessionStore } from '../store/session-store'

const liveOrNull = (session: Session | null): Session | null =>
  session && isLive(session, Date.now()) ? session : null

/** The signed-in session; null when absent or past its expiry (web-coding-standards.md §11). */
export const useSession = (): Session | null => useSessionStore((state) => liveOrNull(state.session))
