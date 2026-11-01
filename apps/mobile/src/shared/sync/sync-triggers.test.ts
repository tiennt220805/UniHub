import {
  startSyncTriggers,
  type AppStateName,
  type NetworkState,
  type TriggerSources,
} from './sync-triggers'

function harness(pending = true) {
  let network: (state: NetworkState) => void = () => {}
  let appState: (state: AppStateName) => void = () => {}
  let tick: () => void = () => {}
  const unsubscribed: string[] = []
  const sources: TriggerSources = {
    onNetworkChange: (listener) => {
      network = listener
      return () => unsubscribed.push('network')
    },
    onAppStateChange: (listener) => {
      appState = listener
      return () => unsubscribed.push('appState')
    },
    setInterval: (callback) => {
      tick = callback
      return 'timer'
    },
    clearInterval: (handle) => unsubscribed.push(`clear:${String(handle)}`),
  }
  const run = jest.fn()
  const state = { pending }
  const stop = startSyncTriggers({ sources, run, hasPending: () => state.pending })
  return {
    run,
    state,
    stop,
    unsubscribed,
    network: (s: NetworkState) => network(s),
    appState: (s: AppStateName) => appState(s),
    tick: () => tick(),
  }
}

const UP: NetworkState = { isConnected: true, isInternetReachable: true }
const DOWN: NetworkState = { isConnected: false, isInternetReachable: false }

describe('when a sync is attempted (M-45)', () => {
  it('runs once at start', () => {
    expect(harness().run).toHaveBeenCalledTimes(1)
  })

  it('runs when the network comes back, not when it merely stays up', () => {
    const h = harness()
    h.run.mockClear()

    h.network(UP) // first reading: nothing to compare to
    h.network(UP)
    expect(h.run).not.toHaveBeenCalled()

    h.network(DOWN)
    h.network(DOWN)
    expect(h.run).not.toHaveBeenCalled()

    h.network(UP)
    expect(h.run).toHaveBeenCalledTimes(1)
  })

  it('connected without internet is not online', () => {
    const h = harness()
    h.run.mockClear()

    h.network(DOWN)
    h.network({ isConnected: true, isInternetReachable: false })
    expect(h.run).not.toHaveBeenCalled()
    h.network({ isConnected: true, isInternetReachable: null })
    expect(h.run).toHaveBeenCalledTimes(1)
  })

  it('runs when the app returns to the foreground, not when it is merely active again', () => {
    const h = harness()
    h.run.mockClear()

    h.appState('active')
    expect(h.run).not.toHaveBeenCalled()
    h.appState('background')
    h.appState('active')
    expect(h.run).toHaveBeenCalledTimes(1)
    h.appState('inactive')
    h.appState('active')
    expect(h.run).toHaveBeenCalledTimes(2)
  })

  it('runs every interval while scans are waiting, and stays quiet when none are', () => {
    const h = harness(true)
    h.run.mockClear()

    h.tick()
    expect(h.run).toHaveBeenCalledTimes(1)

    h.state.pending = false
    h.tick()
    expect(h.run).toHaveBeenCalledTimes(1)
  })

  it('stops listening and clears the timer when stopped', () => {
    const h = harness()

    h.stop()

    expect(h.unsubscribed.sort()).toEqual(['appState', 'clear:timer', 'network'])
  })
})
