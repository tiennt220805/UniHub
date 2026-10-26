import { request } from '@/shared/api/http-client'
import type { components, operations } from '@/shared/api/schema.gen'

export type Payment = components['schemas']['Payment']
export type PaymentStatus = components['schemas']['PaymentStatus']

export function chargePayment(
  registrationId: string,
  paymentMethodToken: string,
  idempotencyKey: string,
) {
  const headers: operations['chargePayment']['parameters']['header'] = {
    'Idempotency-Key': idempotencyKey,
  }
  const body: components['schemas']['CreatePaymentChargeRequest'] = {
    registrationId,
    paymentMethodToken,
  }
  return request<Payment>('/api/v1/payments/charge', {
    method: 'POST',
    headers: { ...headers, 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

export const getPayment = (id: string) =>
  request<Payment>(`/api/v1/payments/${encodeURIComponent(id)}`)
