import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'

import { endOfDay, endOfWeek, startOfDay, ymdOf } from '@/shared/lib/vn-time'
import { aWorkshopPage, aWorkshopSummary } from '@/testing/factories/workshop'
import { server } from '@/testing/msw-server'
import { renderRoute } from '@/testing/render-route'

const TODAY = ymdOf(new Date())
const SUNDAY = endOfWeek(TODAY)

function serveWeek(body: unknown = aWorkshopPage([aWorkshopSummary()])) {
  const queries: URLSearchParams[] = []
  server.use(
    http.get('/api/v1/workshops', ({ request }) => {
      queries.push(new URL(request.url).searchParams)
      return HttpResponse.json(body as object)
    }),
  )
  return queries
}

describe('HomePage', () => {
  it('is the page at "/", not a redirect to the catalog (C73)', async () => {
    serveWeek()

    const { router } = renderRoute('/')

    expect(await screen.findByRole('heading', { level: 1, name: /Tìm workshop hay/ })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/')
  })

  it('asks for this week only: from the start of today to the end of Sunday in Vietnam, three cards at most (D139)', async () => {
    const queries = serveWeek()

    renderRoute('/')
    await screen.findByRole('link', { name: 'Intro to Distributed Locks' })

    expect(Object.fromEntries(queries[0])).toEqual({
      dateFrom: startOfDay(TODAY),
      dateTo: endOfDay(SUNDAY),
      size: '3',
    })
  })

  it('links to the whole week in the catalog with the number of workshops it holds', async () => {
    serveWeek({ items: [aWorkshopSummary()], page: { page: 0, size: 3, totalElements: 6, totalPages: 2 } })

    renderRoute('/')

    const link = await screen.findByRole('link', { name: /Xem tất cả 6 workshop/ })
    expect(link).toHaveAttribute('href', `/workshops?dateFrom=${TODAY}&dateTo=${SUNDAY}`)
  })

  it('says so, with a way on, when nothing is on this week', async () => {
    serveWeek(aWorkshopPage([]))

    renderRoute('/')

    expect(await screen.findByText('Tuần này chưa có workshop')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Xem tất cả workshop' })).toHaveAttribute('href', '/workshops')
  })

  it('shows an error with a retry when the week cannot be loaded', async () => {
    server.use(http.get('/api/v1/workshops', () => HttpResponse.json({ type: 'about:blank', title: 'x', status: 503 }, { status: 503, headers: { 'Content-Type': 'application/problem+json' } })))

    renderRoute('/')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Không tải được các workshop tuần này.')
    expect(within(alert).getByRole('button', { name: 'Thử lại' })).toBeInTheDocument()
  })

  it('hands a search over to the catalog as ?q=', async () => {
    serveWeek()
    server.use(http.get('/api/v1/workshops', () => HttpResponse.json(aWorkshopPage([]))))
    const user = userEvent.setup()
    const { router } = renderRoute('/')

    await user.type(screen.getByLabelText('Tìm workshop theo chủ đề'), 'kubernetes lab')
    await user.click(screen.getByRole('button', { name: 'Tìm' }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/workshops'))
    expect(router.state.location.search).toBe('?q=kubernetes+lab')
  })

  it('opens the whole catalog for an empty search', async () => {
    serveWeek()
    const user = userEvent.setup()
    const { router } = renderRoute('/')

    await user.click(screen.getByRole('button', { name: 'Tìm' }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/workshops'))
    expect(router.state.location.search).toBe('')
  })
})

describe('NotFoundPage', () => {
  it('answers an unknown address with a 404 page and two ways out (S16)', async () => {
    renderRoute('/nowhere/at/all')

    expect(await screen.findByRole('heading', { level: 1, name: 'Không tìm thấy trang' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Về trang chủ' })).toHaveAttribute('href', '/')
    expect(screen.getByRole('link', { name: 'Xem danh sách workshop' })).toHaveAttribute('href', '/workshops')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})
