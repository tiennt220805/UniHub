import type { RegistrationFilters } from './requests'

export const registrationKeys = {
  all: ['registrations'] as const,
  lists: () => [...registrationKeys.all, 'list'] as const,
  list: (filters: RegistrationFilters) => [...registrationKeys.lists(), filters] as const,
  details: () => [...registrationKeys.all, 'detail'] as const,
  detail: (id: string) => [...registrationKeys.details(), id] as const,
}
