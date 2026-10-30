import { useQuery } from '@tanstack/react-query'

import { notificationKeys } from './keys'
import { listMyNotifications, type NotificationFilters } from './requests'

// The inbox is filled by the Backend while the student is elsewhere (a cancellation, a payment
// confirmed in another tab), so coming back to the tab asks again. TanStack's default is the same;
// it is stated here because the page depends on it (Phase 4 checklist 8.1).
export const useMyNotifications = (filters: NotificationFilters) =>
  useQuery({
    queryKey: notificationKeys.list(filters),
    queryFn: () => listMyNotifications(filters),
    refetchOnWindowFocus: true,
  })
