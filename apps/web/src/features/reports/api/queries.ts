import { keepPreviousData, useQuery } from '@tanstack/react-query'

import { reportKeys } from './keys'
import { getWorkshopReport, listWorkshopAttendances } from './requests'

// "Real time" means a few seconds of replica lag plus this refetch (checkin.md §10, D62). TanStack
// refetches on an interval only while the tab is visible (refetchIntervalInBackground defaults to
// false, stated here because the page depends on it), and again when the window regains focus.
export const REPORT_REFETCH_MS = 10_000

export const useWorkshopReport = (workshopId: string) =>
  useQuery({
    queryKey: reportKeys.workshop(workshopId),
    queryFn: () => getWorkshopReport(workshopId),
    refetchInterval: REPORT_REFETCH_MS,
    refetchIntervalInBackground: false,
    refetchOnWindowFocus: true,
  })

/**
 * One page of the check-in list. It has no interval of its own (a second poll would be new load): the list is read when the
 * window regains focus, and again by the page when the report says the number of check-ins changed.
 */
export const useWorkshopAttendances = (workshopId: string, page: number) =>
  useQuery({
    queryKey: reportKeys.attendancePage(workshopId, page),
    queryFn: () => listWorkshopAttendances(workshopId, page),
    placeholderData: keepPreviousData,
  })
