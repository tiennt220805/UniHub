import { useMutation, useQueryClient } from '@tanstack/react-query'

import { toSession } from '../model/session'
import { endSession, startSession } from '../store/session-access'
import { login, type LoginRequest } from './requests'

/**
 * Signs in. The query cache is cleared first: several reads depend on who is asking (a DRAFT
 * workshop, every "my …" list), so nothing fetched anonymously or for another user may survive
 * into this session (web-coding-standards.md §11).
 */
export function useSignIn(options?: { onSuccess?: () => void }) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: LoginRequest) => login(body),
    onSuccess: (response) => {
      queryClient.clear()
      startSession(toSession(response, Date.now()))
      options?.onSuccess?.()
    },
  })
}

export function useSignOut() {
  const queryClient = useQueryClient()
  return () => {
    endSession()
    queryClient.clear()
  }
}
