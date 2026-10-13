import type { WorkshopFilters } from './requests'

export const workshopKeys = {
  all: ['workshops'] as const,
  lists: () => [...workshopKeys.all, 'list'] as const,
  list: (filters: WorkshopFilters) => [...workshopKeys.lists(), filters] as const,
  details: () => [...workshopKeys.all, 'detail'] as const,
  detail: (id: string) => [...workshopKeys.details(), id] as const,
}
