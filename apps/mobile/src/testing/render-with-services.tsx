import { randomUUID } from 'node:crypto'

import { render } from '@testing-library/react-native'
import type { ReactElement } from 'react'

import { createSessionStore, type SessionStore } from '@/shared/auth/session-store'
import type { Api } from '@/shared/api/api-client'
import type { VerificationKey } from '@/shared/crypto/verification-key'
import { ServicesProvider, type Services } from '@/shared/services/services'
import { SyncProvider, type TriggerSources } from '@/shared/sync'
import { openTestDatabase } from '@/testing/database'
import { fakeApi } from '@/testing/fake-api'
import { memorySecretStore } from '@/testing/secret-store'

export const STAFF_ID = '11111111-2222-4333-8444-555555555555'

/** Trigger sources that never fire by themselves; the test calls `fire()` for the timer. */
export function quietSources() {
  let timer: (() => void) | null = null
  const sources: TriggerSources = {
    onNetworkChange: () => () => {},
    onAppStateChange: () => () => {},
    setInterval: (callback) => {
      timer = callback
      return 1
    },
    clearInterval: () => {},
  }
  return { sources, fire: () => timer?.() }
}

export type Harness = ReturnType<typeof makeHarness>

export function makeHarness(
  options: {
    key?: VerificationKey | null
    signedInAs?: string | null
    api?: Api
    clock?: () => Date
  } = {},
) {
  const database = openTestDatabase()
  const secrets = memorySecretStore()
  const api = options.api ?? fakeApi().api
  const session: SessionStore = createSessionStore({
    secrets,
    apiFor: () => api,
    now: () => Date.now(),
    newId: () => randomUUID(),
  })
  const signedInAs = options.signedInAs === undefined ? STAFF_ID : options.signedInAs
  session.store.setState({
    phase: 'ready',
    key: options.key ?? null,
    deviceId: 'device-under-test',
    session:
      signedInAs === null
        ? null
        : {
            accessToken: 'token',
            expiresAt: Date.now() + 1_800_000,
            user: { id: signedInAs, fullName: 'Dev Staff', email: 'staff@dev.test' },
            permissions: ['CHECKIN_SCAN'],
          },
  })
  const services: Services = {
    db: database.db,
    secrets,
    session,
    api,
    now: options.clock ?? (() => new Date('2026-11-01T02:00:00.000Z')),
    newId: () => randomUUID(),
  }
  const sources = quietSources()
  return { ...database, services, session, secrets, sources }
}

export function renderWithServices(harness: Harness, ui: ReactElement, { sync = true } = {}) {
  const tree = sync ? <SyncProvider sources={harness.sources.sources}>{ui}</SyncProvider> : ui
  return render(<ServicesProvider services={harness.services}>{tree}</ServicesProvider>)
}
