import { useMutation, useQueryClient } from '@tanstack/react-query'

import { adminKeys } from './keys'
import {
  cancelWorkshop,
  createWorkshop,
  publishWorkshop,
  updateWorkshop,
  type CreateWorkshopRequest,
  type UpdateWorkshopRequest,
  type Workshop,
} from './requests'

// Every mutation here waits for the server (Rules #8): nothing is updated before the answer. When it comes, the management
// lists are dropped; what the same change does to the other features' caches (the catalog, the workshop detail) is the
// route's job in `onSuccess`, because a feature does not import another (§2, §7). None is retried by itself: none carries an
// Idempotency-Key, so a repeat after a timeout could create a second workshop or publish twice.

type Options = { onSuccess?: (workshop: Workshop) => void }

export function useCreateWorkshop({ onSuccess }: Options = {}) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: CreateWorkshopRequest) => createWorkshop(body),
    onSuccess: (workshop) => {
      void queryClient.invalidateQueries({ queryKey: adminKeys.managedLists() })
      onSuccess?.(workshop)
    },
  })
}

export function useUpdateWorkshop(workshopId: string, { onSuccess }: Options = {}) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: UpdateWorkshopRequest) => updateWorkshop(workshopId, body),
    onSuccess: (workshop) => {
      void queryClient.invalidateQueries({ queryKey: adminKeys.managedLists() })
      onSuccess?.(workshop)
    },
  })
}

export function usePublishWorkshop(workshopId: string, { onSuccess, onSettled }: Options & { onSettled?: () => void } = {}) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => publishWorkshop(workshopId),
    onSuccess: (workshop) => onSuccess?.(workshop),
    // A 409 means the list the user acted on was out of date: it is read again whatever the answer was.
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: adminKeys.managedLists() })
      onSettled?.()
    },
  })
}

export function useCancelWorkshop(workshopId: string, { onSuccess, onSettled }: Options & { onSettled?: () => void } = {}) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => cancelWorkshop(workshopId),
    onSuccess: (workshop) => onSuccess?.(workshop),
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: adminKeys.managedLists() })
      onSettled?.()
    },
  })
}
