import { useState } from 'react'

import { ymdOf } from '@/shared/lib/vn-time'

/**
 * Today's date in Vietnam (`YYYY-MM-DD`), read once when the component mounts. A page left open across midnight keeps
 * yesterday's date until it is reloaded or navigated away from; the catalog's default start date (D138) accepts that.
 */
export function useToday(): string {
  const [today] = useState(() => ymdOf(new Date()))
  return today
}
