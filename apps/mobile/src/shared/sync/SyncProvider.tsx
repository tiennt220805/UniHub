import {
  createContext,
  type ReactNode,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react'

import { countPending, countPendingOfOthers } from '@/shared/db/scan-queue'
import { useServices, useSession } from '@/shared/services/services'

import { createSyncEngine, type SyncOutcome } from './sync-engine'
import { startSyncTriggers, type TriggerSources } from './sync-triggers'

type SyncContextValue = {
  /** Scans the signed-in person has queued and not yet sent. */
  pending: number
  /** Scans queued under someone else's session: they wait until that person signs in. */
  pendingOfOthers: number
  lastOutcome: SyncOutcome | null
  syncing: boolean
  /** Starts a sync now (the button), and also the entry the triggers use. */
  syncNow: () => Promise<void>
  /** Re-reads the counts after something wrote to the queue (a scan). */
  refreshCounts: () => void
}

const SyncContext = createContext<SyncContextValue | null>(null)

export function useSync(): SyncContextValue {
  const value = useContext(SyncContext)
  if (value === null) throw new Error('useSync must be used inside SyncProvider')
  return value
}

type Props = { sources: TriggerSources; children: ReactNode }

/**
 * Runs the sync for the signed-in staff member: when the network comes back, when the app returns
 * to the foreground, and every minute while scans wait (D59), and on demand. An expired token ends
 * the session and leaves every scan on the device.
 */
export function SyncProvider({ sources, children }: Props) {
  const { db, api, session, now } = useServices()
  const staffUserId = useSession((state) => state.session?.user.id ?? null)
  const engine = useRef(createSyncEngine())
  const [counts, setCounts] = useState({ pending: 0, pendingOfOthers: 0 })
  const [lastOutcome, setLastOutcome] = useState<SyncOutcome | null>(null)
  const [syncing, setSyncing] = useState(false)

  const refreshCounts = useCallback(() => {
    if (staffUserId === null) return
    try {
      setCounts({
        pending: countPending(db, staffUserId),
        pendingOfOthers: countPendingOfOthers(db, staffUserId),
      })
    } catch {
      // The database cannot be read right now (the same fault that fails a write): keep the last
      // counts rather than break the screen that is about to say the scan was not saved.
    }
  }, [db, staffUserId])

  const syncNow = useCallback(async () => {
    if (staffUserId === null) return
    setSyncing(true)
    try {
      const outcome = await engine.current({
        db,
        api,
        staffUserId,
        now,
        refreshKey: () => session.refreshKey(),
      })
      if (outcome.kind === 'ALREADY_RUNNING') return
      setLastOutcome(outcome)
      if (outcome.kind === 'SESSION_EXPIRED') await session.signOut({ expired: true })
    } finally {
      setSyncing(false)
      refreshCounts()
    }
  }, [api, db, now, refreshCounts, session, staffUserId])

  useEffect(() => {
    if (staffUserId === null) return
    refreshCounts()
    return startSyncTriggers({
      sources,
      run: () => void syncNow(),
      hasPending: () => {
        try {
          return countPending(db, staffUserId) > 0
        } catch {
          return false
        }
      },
    })
  }, [db, refreshCounts, sources, staffUserId, syncNow])

  const value = useMemo(
    () => ({ ...counts, lastOutcome, syncing, syncNow, refreshCounts }),
    [counts, lastOutcome, syncing, syncNow, refreshCounts],
  )
  return <SyncContext.Provider value={value}>{children}</SyncContext.Provider>
}
