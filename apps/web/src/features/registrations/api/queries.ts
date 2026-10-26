import { useQuery } from '@tanstack/react-query'

import { registrationKeys } from './keys'
import { getRegistration, listMyRegistrations, type RegistrationFilters } from './requests'

export const useRegistration = (id: string) =>
  useQuery({ queryKey: registrationKeys.detail(id), queryFn: () => getRegistration(id) })

export const useMyRegistrations = (filters: RegistrationFilters) =>
  useQuery({
    queryKey: registrationKeys.list(filters),
    queryFn: () => listMyRegistrations(filters),
  })
