import { useEffect, useState } from 'react'

/** Whole seconds left until `until` (an ISO time), ticking once a second; 0 once it has passed. */
export function useCountdown(until: string | null | undefined): number | null {
  const target = until ? new Date(until).getTime() : null
  const [now, setNow] = useState(() => Date.now())

  useEffect(() => {
    if (target === null) return
    // `now` was taken when the hook mounted, which can be long before `until` arrived: refresh it
    // right away rather than showing the stale remainder for the first second.
    const tick = () => setNow(Date.now())
    const first = setTimeout(tick, 0)
    const timer = setInterval(tick, 1000)
    return () => {
      clearTimeout(first)
      clearInterval(timer)
    }
  }, [target])

  return target === null ? null : Math.max(0, Math.ceil((target - now) / 1000))
}
