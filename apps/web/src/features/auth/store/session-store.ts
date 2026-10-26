import { create } from 'zustand'

import { fromStored, isLive, type Session, type StoredSession, toStored } from '../model/session'

// sessionStorage (auth-and-identity.md §7, checklist D14): a reload keeps the session, closing the
// tab ends it. An expired snapshot is discarded at start-up; end() runs on sign-out and on a 401.
const KEY = 'unihub.session'

// Storage can be missing or throw (a locked-down browser, a private window): a session that cannot
// be persisted is still a working in-memory session.
function readStored(): StoredSession | null {
  try {
    const raw = sessionStorage.getItem(KEY)
    return raw ? (JSON.parse(raw) as StoredSession) : null
  } catch {
    return null
  }
}

function isSessionShape(value: unknown): value is StoredSession {
  const s = value as Partial<StoredSession> | null
  return (
    typeof s?.accessToken === 'string' &&
    typeof s.expiresAt === 'number' &&
    Array.isArray(s.permissions) &&
    typeof s.user === 'object' &&
    s.user !== null
  )
}

function forget() {
  try {
    sessionStorage.removeItem(KEY)
  } catch {
    // nothing to forget
  }
}

export function restoreSession(now: number = Date.now()): Session | null {
  const stored = readStored()
  if (isSessionShape(stored) && isLive(fromStored(stored), now)) return fromStored(stored)
  forget()
  return null
}

type SessionState = {
  session: Session | null
  start: (session: Session) => void
  end: () => void
}

export const useSessionStore = create<SessionState>()((set) => ({
  session: restoreSession(),
  start: (session) => {
    try {
      sessionStorage.setItem(KEY, JSON.stringify(toStored(session)))
    } catch {
      // kept in memory only
    }
    set({ session })
  },
  end: () => {
    forget()
    set({ session: null })
  },
}))
