import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { describe, expect, it } from 'vitest'

import { routes } from '@/routes'
import { server } from '@/test/msw-server'
import type { components } from '@/types/schema.gen'

type WorkshopListResponse = components['schemas']['WorkshopListResponse']

function renderAt(path: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
}

describe('WorkshopCatalogPage', () => {
  it('shows the empty state when GET /api/v1/workshops returns no items', async () => {
    const empty: WorkshopListResponse = {
      items: [],
      page: { page: 0, size: 20, totalElements: 0, totalPages: 0 },
    }
    server.use(http.get('/api/v1/workshops', () => HttpResponse.json(empty)))

    renderAt('/workshops')

    expect(await screen.findByText('Chưa có workshop nào.')).toBeInTheDocument()
  })

  it('renders each workshop returned by the API', async () => {
    const body: WorkshopListResponse = {
      items: [
        {
          id: '3f2b8c1e-6d4a-4a57-9b0e-1c2d3e4f5a6b',
          title: 'Intro to Distributed Locks',
          location: 'Hall A',
          startTime: '2026-11-01T02:00:00Z',
          status: 'PUBLISHED',
        },
      ],
      page: { page: 0, size: 20, totalElements: 1, totalPages: 1 },
    }
    server.use(http.get('/api/v1/workshops', () => HttpResponse.json(body)))

    renderAt('/workshops')

    expect(await screen.findByText(/Intro to Distributed Locks/)).toBeInTheDocument()
    expect(screen.queryByText('Chưa có workshop nào.')).not.toBeInTheDocument()
  })

  it('shows an error message instead of crashing when the API fails', async () => {
    server.use(http.get('/api/v1/workshops', () => new HttpResponse(null, { status: 500 })))

    renderAt('/workshops')

    expect(await screen.findByRole('alert')).toHaveTextContent('Không tải được danh sách workshop.')
  })

  it('redirects / to the catalog', async () => {
    server.use(
      http.get('/api/v1/workshops', () =>
        HttpResponse.json({
          items: [],
          page: { page: 0, size: 20, totalElements: 0, totalPages: 0 },
        }),
      ),
    )

    renderAt('/')

    expect(await screen.findByText('Chưa có workshop nào.')).toBeInTheDocument()
  })
})
