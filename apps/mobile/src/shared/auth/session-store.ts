import { createStore } from 'zustand/vanilla'

import type { Api } from '@/shared/api/api-client'
import type { VerificationKey } from '@/shared/crypto/verification-key'
import type { SecretStore } from '@/shared/secure/secret-store'

import { getOrCreateDeviceId } from './device-id'
import { endSession, restoreSession, type Session, signIn, type SignInResult } from './session'
import { loadVerificationKey, refreshVerificationKey } from './verification-key'

export type SessionState = {
  /** `loading` until the stored session, key and device id have been read once. */
  phase: 'loading' | 'ready'
  session: Session | null
  key: VerificationKey | null
  deviceId: string | null
  /** Set when a session ended because the token expired, so the sign-in screen can say why. */
  expired: boolean
}

type Deps = {
  secrets: SecretStore
  apiFor: (token: string | null) => Api
  now: () => number
  newId: () => string
}

/** The signed-in person, the cached key and the device id: the state every screen and the sync reads. */
export function createSessionStore({ secrets, apiFor, now, newId }: Deps) {
  const store = createStore<SessionState>(() => ({
    phase: 'loading',
    session: null,
    key: null,
    deviceId: null,
    expired: false,
  }))

  return {
    store,
    get: store.getState,

    async bootstrap(): Promise<void> {
      const [session, key, deviceId] = await Promise.all([
        restoreSession(secrets),
        loadVerificationKey(secrets),
        getOrCreateDeviceId(secrets, newId),
      ])
      store.setState({ phase: 'ready', session, key, deviceId, expired: false })
    },

    async signIn(email: string, password: string): Promise<SignInResult> {
      const result = await signIn(
        { apiFor, secrets, now },
        email,
        password,
        store.getState().key !== null,
      )
      if (result.kind === 'SIGNED_IN') {
        store.setState({
          session: result.session,
          key: await loadVerificationKey(secrets),
          expired: false,
        })
      }
      return result
    },

    /** Ends the session; scans, key and device id stay (see `endSession`). */
    async signOut(options: { expired?: boolean } = {}): Promise<void> {
      await endSession(secrets)
      store.setState({ session: null, expired: options.expired === true })
    },

    async refreshKey(): Promise<void> {
      const token = store.getState().session?.accessToken ?? null
      if (token === null) return
      const refreshed = await refreshVerificationKey(apiFor(token), secrets)
      if (refreshed.outcome === 'UPDATED') store.setState({ key: refreshed.key })
    },
  }
}

export type SessionStore = ReturnType<typeof createSessionStore>
