import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useRef } from 'react'

import { isTransient, problemSlug } from '@/shared/api/errors'
import { retryDelayMs, shouldRetryAutomatically } from '@/shared/api/retry'
import { registrationKeys } from './keys'
import { createRegistration, type Registration } from './requests'

/**
 * Registers the signed-in user for a workshop.
 *
 * One `Idempotency-Key` per intent (web-coding-standards.md §10): created on the first submit and
 * kept for every retry and double-click, so a request that did commit is replayed, not repeated.
 * The intent ends — the next submit gets a new key — on success and on any definitive answer. It
 * continues, with the same key, after a network failure, a 429, a 503, or "this key is still being
 * processed". A network failure or a 503 is also retried automatically, at most three times,
 * honouring Retry-After (§7).
 */
export function useRegisterForWorkshop(
  workshopId: string,
  options: { onSuccess?: (registration: Registration) => void } = {},
) {
  const queryClient = useQueryClient()
  const intentKey = useRef<string | null>(null)

  const mutation = useMutation({
    mutationFn: (idempotencyKey: string) => createRegistration(workshopId, idempotencyKey),
    retry: shouldRetryAutomatically,
    retryDelay: retryDelayMs,
    onSuccess: (registration) => {
      intentKey.current = null
      // Read-after-write: the response is the post-write state; a Replica-backed list can lag it.
      queryClient.setQueryData(registrationKeys.detail(registration.id), registration)
      void queryClient.invalidateQueries({ queryKey: registrationKeys.lists() })
      options.onSuccess?.(registration) // cross-feature effects are the route's job (§7)
    },
    onError: (error) => {
      const keyStillBusy = problemSlug(error) === 'idempotency-key-in-use'
      if (!isTransient(error) && !keyStillBusy) intentKey.current = null
    },
  })

  return {
    register: () => {
      if (mutation.isPending) return // a double click is the same intent, already in flight
      intentKey.current ??= crypto.randomUUID()
      mutation.mutate(intentKey.current)
    },
    isPending: mutation.isPending,
    /** Automatic retries made so far for the attempt in flight. */
    retryCount: mutation.failureCount,
    error: mutation.error,
    registration: mutation.data,
  }
}
