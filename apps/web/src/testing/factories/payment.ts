import type { components } from '@/shared/api/schema.gen'
import { REGISTRATION_ID } from './registration'

type Payment = components['schemas']['Payment']

export const PAYMENT_ID = '5c4d3e2f-1a0b-4c9d-8e7f-6a5b4c3d2e1f'

export function aPayment(overrides: Partial<Payment> = {}): Payment {
  const status = overrides.status ?? 'PENDING'
  return {
    id: PAYMENT_ID,
    registrationId: REGISTRATION_ID,
    amount: 150000,
    currency: 'VND',
    gateway: 'STRIPE',
    gatewayTransactionId: null,
    status,
    initiatedAt: '2026-10-26T01:00:00Z',
    resolvedAt: status === 'PENDING' ? null : '2026-10-26T01:00:03Z',
    ...overrides,
  } as Payment
}
