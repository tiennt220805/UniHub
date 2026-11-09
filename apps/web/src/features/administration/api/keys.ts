import type { ManagedWorkshopFilters } from './requests'

export const adminKeys = {
  all: ['administration'] as const,
  managedLists: () => [...adminKeys.all, 'managed-list'] as const,
  managedList: (filters: ManagedWorkshopFilters) => [...adminKeys.managedLists(), filters] as const,
}
