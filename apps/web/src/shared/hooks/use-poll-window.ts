import { useCallback, useEffect, useState } from 'react'

export const POLL_WINDOW_MS = 120_000

/**
 * A bounded polling window: `polling` is true while `active` holds and fewer than `maxMs` have
 * passed since it began. When the window runs out `expired` becomes true and polling stops, so a
 * status that never settles ends in "still processing" plus a manual refresh instead of an endless
 * poll (checklist D15). `restart` opens a fresh window, and so does `active` turning on again: an
 * expired window belongs to the thing that was being followed, not to the page.
 */
export function usePollWindow(active: boolean, maxMs: number = POLL_WINDOW_MS) {
  const [run, setRun] = useState(0)
  const [expiredRun, setExpiredRun] = useState<number | null>(null)
  const [wasActive, setWasActive] = useState(active)

  // Adjusting state while rendering (React's documented way to react to a prop change without an
  // extra effect pass): going from inactive to active is a new window.
  if (active !== wasActive) {
    setWasActive(active)
    if (active) setRun((n) => n + 1)
  }

  useEffect(() => {
    if (!active) return
    const timer = setTimeout(() => setExpiredRun(run), maxMs)
    return () => clearTimeout(timer)
  }, [active, run, maxMs])

  const expired = active && expiredRun === run
  const restart = useCallback(() => setRun((n) => n + 1), [])

  return { polling: active && !expired, expired, restart }
}
