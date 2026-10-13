import { QueryClient } from '@tanstack/react-query'

import { AppError } from '@/shared/api/errors'

declare module '@tanstack/react-query' {
  interface Register {
    defaultError: AppError
  }
}

const isClientError = (error: unknown) =>
  error instanceof AppError && error.status !== null && error.status >= 400 && error.status < 500

// Queries never retry a 4xx; mutations never retry automatically (web-coding-standards.md §7).
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: (failureCount, error) => !isClientError(error) && failureCount < 3 }, // 3 = TanStack's default
    mutations: { retry: false },
  },
})
