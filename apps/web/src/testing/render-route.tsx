import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router'

import { wireHttpClient } from '@/app/providers/wire-http-client'
import { routes } from '@/app/router/routes'

// The administration pages are lazy in the app (task 7.5). A test has no network to wait on, but the first import() of a
// page would still be transformed and evaluated inside the test's own 1 s findBy window and fail at random. Importing
// them here, while the file is loaded, is the test's version of "the chunk has arrived"; lazy() then resolves from the
// module cache.
await Promise.all([
  import('@/routes/admin/AdminIndexPage'),
  import('@/routes/admin/WorkshopCreatePage'),
  import('@/routes/admin/WorkshopEditPage'),
  import('@/routes/admin/WorkshopListPage'),
  import('@/routes/admin/WorkshopReportPage'),
])

// Renders the real route table at `path`, so a page test exercises routing and params the same way
// the app does. Each call gets a fresh QueryClient with retries off (web-coding-standards.md §12):
// a failing request must surface on the first attempt, and no cache leaks between tests.
export function renderRoute(path: string, options: { state?: unknown } = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  // The same wiring as the app: a 401 on a request with a token must end the session in tests too.
  wireHttpClient(queryClient)
  const router = createMemoryRouter(routes, {
    initialEntries: [{ pathname: path.split('?')[0], search: path.includes('?') ? `?${path.split('?')[1]}` : '', state: options.state }],
  })
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { router, queryClient }
}
