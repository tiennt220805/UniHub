export type NetworkState = { isConnected: boolean | null; isInternetReachable: boolean | null }
export type AppStateName = 'active' | 'background' | 'inactive' | 'unknown' | 'extension'

export type TriggerSources = {
  /** Calls back with every connectivity change (NetInfo). Returns the unsubscribe function. */
  onNetworkChange: (listener: (state: NetworkState) => void) => () => void
  /** Calls back with every app state change (AppState). */
  onAppStateChange: (listener: (state: AppStateName) => void) => () => void
  setInterval: (callback: () => void, ms: number) => unknown
  clearInterval: (handle: unknown) => void
}

export const SYNC_INTERVAL_MS = 60_000

const online = (state: NetworkState) =>
  state.isConnected === true && state.isInternetReachable !== false

/**
 * When a sync is attempted: once at start, when the network comes back (not on every event while it
 * stays up), when the app returns to the foreground, and every minute while scans are waiting. The
 * engine itself makes overlapping triggers harmless; this only decides when to ask.
 */
export function startSyncTriggers(options: {
  sources: TriggerSources
  run: () => void
  hasPending: () => boolean
  intervalMs?: number
}): () => void {
  const { sources, run, hasPending, intervalMs = SYNC_INTERVAL_MS } = options
  let wasOnline: boolean | null = null
  let lastAppState: AppStateName | null = null

  const stopNetwork = sources.onNetworkChange((state) => {
    const nowOnline = online(state)
    if (nowOnline && wasOnline === false) run()
    wasOnline = nowOnline
  })
  const stopAppState = sources.onAppStateChange((state) => {
    if (state === 'active' && lastAppState !== null && lastAppState !== 'active') run()
    lastAppState = state
  })
  const timer = sources.setInterval(() => {
    if (hasPending()) run()
  }, intervalMs)
  run()

  return () => {
    stopNetwork()
    stopAppState()
    sources.clearInterval(timer)
  }
}
