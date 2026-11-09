import { keepPreviousData, useQuery } from '@tanstack/react-query'

import { workshopKeys } from './keys'
import { getWorkshop, listWorkshops, type WorkshopFilters } from './requests'

// Seat availability is near-real-time by design: the backend caches the seats entry for 3 s
// (workshop.md §2), so asking more often cannot show a fresher number (checklist D15).
export const SEATS_STALE_MS = 3_000
export const SEATS_POLL_MS = 5_000

/**
 * A page of the catalog. The previous page's data stays on screen while the next one loads, so changing a filter
 * dims the list instead of blanking it. `enabled: false` is for a query the caller already knows the server would
 * refuse (a backwards date range).
 */
export const useWorkshops = (filters: WorkshopFilters, options: { enabled?: boolean } = {}) =>
  useQuery({
    queryKey: workshopKeys.list(filters),
    queryFn: () => listWorkshops(filters),
    enabled: options.enabled ?? true,
    placeholderData: keepPreviousData,
  })

/**
 * One workshop. With `liveSeats` the query refreshes every 5 s so the seat count follows the other
 * students, only while the tab is visible (TanStack skips an interval tick for a hidden tab) and
 * only while the calling page is mounted. The count stays advisory: a 409 after a non-zero count is
 * a normal outcome, never an error state.
 */
export const useWorkshop = (id: string, options: { liveSeats?: boolean } = {}) =>
  useQuery({
    queryKey: workshopKeys.detail(id),
    queryFn: () => getWorkshop(id),
    ...(options.liveSeats && {
      staleTime: SEATS_STALE_MS,
      refetchInterval: SEATS_POLL_MS,
      refetchIntervalInBackground: false,
    }),
  })
