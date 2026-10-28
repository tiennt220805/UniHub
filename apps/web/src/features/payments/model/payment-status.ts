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

/**
 * The money is owed back and the refund has not been made yet. It is made in the background (the
 * refund consumer), so the status moves on to REFUNDED by itself: a screen showing this status is
 * showing something that is still happening.
 */
export const isRefundPending = (status: PaymentStatus): boolean => status === 'REFUND_REQUIRED'
