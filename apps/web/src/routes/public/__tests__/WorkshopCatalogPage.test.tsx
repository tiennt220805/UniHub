import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'

import { aWorkshop, aWorkshopPage, aWorkshopSummary, WORKSHOP_ID } from '@/testing/factories/workshop'
import { server } from '@/testing/msw-server'
import { renderRoute } from '@/testing/render-route'

describe('WorkshopCatalogPage', () => {
  it('shows the empty state when GET /api/v1/workshops returns no items', async () => {
    server.use(http.get('/api/v1/workshops', () => HttpResponse.json(aWorkshopPage([]))))

    renderRoute('/workshops')

    expect(await screen.findByText('Chưa có workshop nào.')).toBeInTheDocument()
  })

  it('renders title, location and start time of each workshop returned by the API', async () => {
    server.use(http.get('/api/v1/workshops', () => HttpResponse.json(aWorkshopPage([aWorkshopSummary()]))))

    renderRoute('/workshops')

    const item = (await screen.findByText('Intro to Distributed Locks')).closest('li')!
    expect(item).toHaveTextContent('Hall A')
    // 02:00Z rendered in UniHub's operating zone, Asia/Ho_Chi_Minh (UTC+7).
    expect(item).toHaveTextContent('09:00 1 thg 11, 2026')
    expect(screen.queryByText('Chưa có workshop nào.')).not.toBeInTheDocument()
  })

  it('links each workshop title to its detail page', async () => {
    server.use(http.get('/api/v1/workshops', () => HttpResponse.json(aWorkshopPage([aWorkshopSummary()]))))

    renderRoute('/workshops')

    expect(await screen.findByRole('link', { name: 'Intro to Distributed Locks' })).toHaveAttribute(
      'href',
      `/workshops/${WORKSHOP_ID}`,
    )
  })

  it('navigates from the list to the detail page on click', async () => {
    server.use(
      http.get('/api/v1/workshops', () => HttpResponse.json(aWorkshopPage([aWorkshopSummary()]))),
      http.get(`/api/v1/workshops/${WORKSHOP_ID}`, () => HttpResponse.json(aWorkshop())),
    )

    renderRoute('/workshops')
    await userEvent.click(await screen.findByRole('link', { name: 'Intro to Distributed Locks' }))

    expect(await screen.findByText('Dr. Lan Nguyen')).toBeInTheDocument()
  })

  it('shows an error message with the correlation id when the API fails', async () => {
    server.use(
      http.get('/api/v1/workshops', () =>
        HttpResponse.json(
          { type: 'about:blank', title: 'Internal Server Error', status: 500, correlationId: '4bf92f3577b34da6a3ce929d0e0e4736' },
          { status: 500, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    )

    renderRoute('/workshops')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Không tải được danh sách workshop.')
    expect(alert).toHaveTextContent('Mã tham chiếu: 4bf92f3577b34da6a3ce929d0e0e4736')
  })

  it('tells the user when to retry after a 429', async () => {
    server.use(
      http.get('/api/v1/workshops', () =>
        HttpResponse.json(
          { type: 'about:blank', title: 'Too Many Requests', status: 429 },
          { status: 429, headers: { 'Content-Type': 'application/problem+json', 'Retry-After': '30' } },
        ),
      ),
    )

    renderRoute('/workshops')

    expect(await screen.findByRole('alert')).toHaveTextContent('Vui lòng thử lại sau 30 giây.')
  })

  it('redirects / to the catalog', async () => {
    server.use(http.get('/api/v1/workshops', () => HttpResponse.json(aWorkshopPage([]))))

    renderRoute('/')

    expect(await screen.findByText('Chưa có workshop nào.')).toBeInTheDocument()
  })
})
