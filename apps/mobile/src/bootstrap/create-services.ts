import NetInfo from '@react-native-community/netinfo'
import * as Crypto from 'expo-crypto'
import { AppState } from 'react-native'

import { createApi } from '@/shared/api/api-client'
import { createSessionStore } from '@/shared/auth/session-store'
import { env } from '@/shared/config/env'
import { database } from '@/shared/db/open'
import { expoSecretStore } from '@/shared/secure/secret-store'
import type { Services } from '@/shared/services/services'
import type { TriggerSources } from '@/shared/sync'

/** The composition root: the real database, keystore, network and clock the app runs on. */
export function createServices(): Services {
  const secrets = expoSecretStore
  const now = () => new Date()
  const newId = () => Crypto.randomUUID()
  const apiFor = (token: string | null) =>
    createApi({ baseUrl: env.apiBaseUrl, token: () => token })

  const session = createSessionStore({ secrets, apiFor, now: () => Date.now(), newId })
  // This client reads the token of the session that is current when each request is made.
  const api = createApi({
    baseUrl: env.apiBaseUrl,
    token: () => session.get().session?.accessToken ?? null,
  })

  return { db: database(), secrets, session, api, now, newId }
}

export const syncSources: TriggerSources = {
  onNetworkChange: (listener) =>
    NetInfo.addEventListener((state) =>
      listener({ isConnected: state.isConnected, isInternetReachable: state.isInternetReachable }),
    ),
  onAppStateChange: (listener) => {
    const subscription = AppState.addEventListener('change', listener)
    return () => subscription.remove()
  },
  setInterval: (callback, ms) => setInterval(callback, ms),
  clearInterval: (handle) => clearInterval(handle as ReturnType<typeof setInterval>),
}
