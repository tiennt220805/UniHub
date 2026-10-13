import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router'

import { routes } from '@/app/router/routes'

// Renders the real route table at `path`, so a page test exercises routing and params the same way
// the app does. Each call gets a fresh QueryClient with retries off (web-coding-standards.md §12):
// a failing request must surface on the first attempt, and no cache leaks between tests.
export function renderRoute(path: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { router }
}
