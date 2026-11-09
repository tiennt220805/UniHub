import { useState } from 'react'

import { useWorkshop } from '@/features/workshops'

/**
 * Whether the workshop's start has passed on the clock of this browser, once the workshop has loaded (D150: the server
 * does not say "past"). The clock is read once when the row mounts; a page left open across a start time shows the old
 * answer until it is reloaded, which is the same bargain `useToday` makes.
 */
export function useHasStarted(workshopId: string): boolean {
  const workshop = useWorkshop(workshopId)
  const [now] = useState(() => Date.now())
  return workshop.data !== undefined && new Date(workshop.data.startTime).getTime() < now
}
