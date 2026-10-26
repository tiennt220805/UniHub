import type { components } from '@/shared/api/schema.gen'
import { STUDENT_ID } from './session'
import { WORKSHOP_ID } from './workshop'

type Registration = components['schemas']['Registration']
type RegistrationSummary = components['schemas']['RegistrationSummary']
type RegistrationListResponse = components['schemas']['RegistrationListResponse']

export const REGISTRATION_ID = '9b8a7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d'

export function aRegistration(overrides: Partial<Registration> = {}): Registration {
  return {
    id: REGISTRATION_ID,
    workshopId: WORKSHOP_ID,
    userId: STUDENT_ID,
    ticketType: 'FREE',
    status: 'CONFIRMED',
    holdExpiresAt: null,
    ticketCode: null,
    qrPayload: null,
    createdAt: '2026-10-26T01:00:00Z',
    updatedAt: '2026-10-26T01:00:00Z',
    ...overrides,
  }
}

/** A paid registration still inside its 15-minute hold. */
export function aReservedRegistration(overrides: Partial<Registration> = {}): Registration {
  return aRegistration({
    ticketType: 'PAID',
    status: 'RESERVED',
    holdExpiresAt: new Date(Date.now() + 15 * 60 * 1000).toISOString(),
    ...overrides,
  })
}

export function aRegistrationSummary(overrides: Partial<RegistrationSummary> = {}): RegistrationSummary {
  return {
    id: REGISTRATION_ID,
    workshopId: WORKSHOP_ID,
    userId: STUDENT_ID,
    ticketType: 'FREE',
    status: 'CONFIRMED',
    createdAt: '2026-10-26T01:00:00Z',
    ...overrides,
  }
}

export function aRegistrationPage(items: RegistrationSummary[]): RegistrationListResponse {
  return {
    items,
    page: { page: 0, size: 20, totalElements: items.length, totalPages: items.length === 0 ? 0 : 1 },
  }
}
