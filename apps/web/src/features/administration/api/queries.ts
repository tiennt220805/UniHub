import { keepPreviousData, useQuery } from '@tanstack/react-query'

import { adminKeys } from './keys'
import { listManagedWorkshops, type ManagedWorkshopFilters } from './requests'

/** A page of the management list. The previous page stays on screen while the next one loads, so a tab or a search dims it. */
export const useManagedWorkshops = (filters: ManagedWorkshopFilters) =>
  useQuery({
    queryKey: adminKeys.managedList(filters),
    queryFn: () => listManagedWorkshops(filters),
    placeholderData: keepPreviousData,
  })
