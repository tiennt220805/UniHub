import { request } from '@/shared/api/http-client'
import type { components, operations } from '@/shared/api/schema.gen'
import { toQueryString } from '@/shared/lib/query-string'

export type Registration = components['schemas']['Registration']
export type RegistrationSummary = components['schemas']['RegistrationSummary']
export type RegistrationListResponse = components['schemas']['RegistrationListResponse']
export type RegistrationStatus = components['schemas']['RegistrationStatus']
export type RegistrationFilters = NonNullable<operations['listMyRegistrations']['parameters']['query']>

export function createRegistration(workshopId: string, idempotencyKey: string) {
  // Typed against the contract: a misspelled header name fails to compile (web-coding-standards.md §10).
  const headers: operations['createRegistration']['parameters']['header'] = {
    'Idempotency-Key': idempotencyKey,
  }
  const body: components['schemas']['CreateRegistrationRequest'] = { workshopId }
  return request<Registration>('/api/v1/registrations', {
    method: 'POST',
    headers: { ...headers, 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

export const getRegistration = (id: string) =>
  request<Registration>(`/api/v1/registrations/${encodeURIComponent(id)}`)

export const listMyRegistrations = (filters: RegistrationFilters) =>
  request<RegistrationListResponse>(`/api/v1/registrations${toQueryString(filters)}`)
