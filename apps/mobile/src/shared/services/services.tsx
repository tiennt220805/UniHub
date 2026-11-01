import { createContext, type ReactNode, useContext } from 'react'
import { useStore } from 'zustand'

import type { Api } from '@/shared/api/api-client'
import type { SessionState, SessionStore } from '@/shared/auth/session-store'
import type { Db } from '@/shared/db/types'
import type { SecretStore } from '@/shared/secure/secret-store'

/**
 * What the screens need from the outside world, supplied once at the root (`app/_layout.tsx`) and
 * replaced in tests: the database, the API, the keystore, the session, the clock and an id source.
 */
export type Services = {
  db: Db
  secrets: SecretStore
  session: SessionStore
  /** An API client that sends the signed-in session's token. */
  api: Api
  now: () => Date
  newId: () => string
}

const ServicesContext = createContext<Services | null>(null)

export function ServicesProvider({
  services,
  children,
}: {
  services: Services
  children: ReactNode
}) {
  return <ServicesContext.Provider value={services}>{children}</ServicesContext.Provider>
}

export function useServices(): Services {
  const services = useContext(ServicesContext)
  if (services === null) throw new Error('useServices must be used inside ServicesProvider')
  return services
}

export function useSession<T>(select: (state: SessionState) => T): T {
  return useStore(useServices().session.store, select)
}
