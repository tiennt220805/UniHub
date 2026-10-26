import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useRef } from 'react'

import { isTransient, problemSlug } from '@/shared/api/errors'
import { paymentKeys } from './keys'
import { chargePayment, type Payment } from './requests'

type Intent = { token: string; key: string }

/**
 * Charges a RESERVED registration with a payment-method token.
 *
 * One `Idempotency-Key` per intent (web-coding-standards.md §10). A different token is a different
 * request body, so it is a different intent: a key is never reused with another body (the server
 * answers 422). A `201` ends the intent whatever the payment's status: a payment that came back
 * FAILED or CANCELLED is retried as a new attempt with a new key, because the old key would only
 * replay the old outcome. A transient failure — no response, 429, 503, "key still being processed"
 * — keeps the key, so a retry cannot charge twice.
 *
 * No automatic retry: a 503 here means the gateway was not reached and the seat is still held, and
 * the payment flow tells the user at once (web-coding-standards.md §6).
 */
export function useChargePayment(
  registrationId: string,
  options: { onSuccess?: (payment: Payment) => void } = {},
) {
  const queryClient = useQueryClient()
  const intent = useRef<Intent | null>(null)

  const mutation = useMutation({
    mutationFn: (current: Intent) => chargePayment(registrationId, current.token, current.key),
    onSuccess: (payment) => {
      intent.current = null
      // Read-after-write: the response is the post-write state of this payment.
      queryClient.setQueryData(paymentKeys.detail(payment.id), payment)
      options.onSuccess?.(payment) // refreshing the registration is the route's job (§7)
    },
    onError: (error) => {
      const keyStillBusy = problemSlug(error) === 'idempotency-key-in-use'
      if (!isTransient(error) && !keyStillBusy) intent.current = null
    },
  })

  return {
    charge: (token: string) => {
      if (mutation.isPending) return
      if (intent.current?.token !== token) intent.current = { token, key: crypto.randomUUID() }
      mutation.mutate(intent.current)
    },
    isPending: mutation.isPending,
    error: mutation.error,
  }
}
