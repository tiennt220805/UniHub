import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'

import { server } from '@/test/msw-server'
import { renderRoute } from '@/test/render-route'
import type { components } from '@/types/schema.gen'

type Workshop = components['schemas']['Workshop']
type WorkshopListResponse = components['schemas']['WorkshopListResponse']

const WORKSHOP_ID = '3f2b8c1e-6d4a-4a57-9b0e-1c2d3e4f5a6b'

const oneWorkshop: WorkshopListResponse = {
  items: [
    {
      id: WORKSHOP_ID,
      title: 'Intro to Distributed Locks',
      location: 'Hall A',
      startTime: '2026-11-01T02:00:00Z',
      status: 'PUBLISHED',
    },
  ],
  page: { page: 0, size: 20, totalElements: 1, totalPages: 1 },
}

describe('WorkshopCatalogPage', () => {
  it('shows the empty state when GET /api/v1/workshops returns no items', async () => {
    const empty: WorkshopListResponse = {
      items: [],
      page: { page: 0, size: 20, totalElements: 0, totalPages: 0 },
    }
    server.use(http.get('/api/v1/workshops', () => HttpResponse.json(empty)))

    renderRoute('/workshops')

    expect(await screen.findByText('Chưa có workshop nào.')).toBeInTheDocument()
  })

  it('renders title, location and start time of each workshop returned by the API', async () => {
    server.use(http.get('/api/v1/workshops', () => HttpResponse.json(oneWorkshop)))

    renderRoute('/workshops')

    const item = (await screen.findByText('Intro to Distributed Locks')).closest('li')!
    expect(item).toHaveTextContent('Hall A')
    // 02:00Z rendered in UniHub's operating zone, Asia/Ho_Chi_Minh (UTC+7).
    expect(item).toHaveTextContent('09:00 1 thg 11, 2026')
    expect(screen.queryByText('Chưa có workshop nào.')).not.toBeInTheDocument()
  })

  it('links each workshop title to its detail page', async () => {
    server.use(http.get('/api/v1/workshops', () => HttpResponse.json(oneWorkshop)))

    renderRoute('/workshops')

    expect(await screen.findByRole('link', { name: 'Intro to Distributed Locks' })).toHaveAttribute(
      'href',
      `/workshops/${WORKSHOP_ID}`,
    )
  })

  it('navigates from the list to the detail page on click', async () => {
    const detail: Workshop = {
      id: WORKSHOP_ID,
      title: 'Intro to Distributed Locks',
      speakerName: 'Dr. Lan Nguyen',
      location: 'Hall A',
      startTime: '2026-11-01T02:00:00Z',
      endTime: '2026-11-01T04:00:00Z',
      capacity: 50,
      seatsTaken: 15,
      isPaid: false,
      price: null,
      hasBrochure: false,
      status: 'PUBLISHED',
      version: 3,
    }
    server.use(
      http.get('/api/v1/workshops', () => HttpResponse.json(oneWorkshop)),
      http.get(`/api/v1/workshops/${WORKSHOP_ID}`, () => HttpResponse.json(detail)),
    )

    renderRoute('/workshops')
    await userEvent.click(await screen.findByRole('link', { name: 'Intro to Distributed Locks' }))

    expect(await screen.findByText('Dr. Lan Nguyen')).toBeInTheDocument()
  })

  it('shows an error message instead of crashing when the API fails', async () => {
    server.use(http.get('/api/v1/workshops', () => new HttpResponse(null, { status: 500 })))

    renderRoute('/workshops')

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

    renderRoute('/')

    expect(await screen.findByText('Chưa có workshop nào.')).toBeInTheDocument()
  })
})
