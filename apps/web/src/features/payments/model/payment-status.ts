import type { PaymentStatus } from '../api/requests'

/**
 * The payment has left PENDING and nothing more will change it by itself *as far as the payment
 * goes*. SUCCEEDED is not the end of the story for the student: their registration is confirmed a
 * moment later by a consumer, which the route watches separately.
 */
export const isPaymentResolved = (status: PaymentStatus): boolean => status !== 'PENDING'

/** Resolved without taking the student's money, or giving it back: the student may try again. */
export const isPaymentRetryable = (status: PaymentStatus): boolean =>
  status === 'FAILED' || status === 'CANCELLED'
