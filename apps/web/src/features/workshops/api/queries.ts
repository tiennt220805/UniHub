import { useQuery } from '@tanstack/react-query'

import { workshopKeys } from './keys'
import { getWorkshop, listWorkshops, type WorkshopFilters } from './requests'

export const useWorkshops = (filters: WorkshopFilters) =>
  useQuery({ queryKey: workshopKeys.list(filters), queryFn: () => listWorkshops(filters) })

// seatsTaken refresh cadence is not fixed by any source yet (web-coding-standards.md §13 item 7):
// TanStack defaults until it is.
export const useWorkshop = (id: string) =>
  useQuery({ queryKey: workshopKeys.detail(id), queryFn: () => getWorkshop(id) })
