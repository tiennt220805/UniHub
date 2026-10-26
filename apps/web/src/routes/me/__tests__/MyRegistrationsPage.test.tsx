import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'

import { aRegistrationPage, aRegistrationSummary } from '@/testing/factories/registration'
import { signInAs } from '@/testing/factories/session'
import { aWorkshop } from '@/testing/factories/workshop'
import { server } from '@/testing/msw-server'
import { problem } from '@/testing/problem'
import { renderRoute } from '@/testing/render-route'

const serveWorkshops = () =>
  server.use(
    http.get('/api/v1/workshops/:id', ({ params }) =>
      HttpResponse.json(aWorkshop({ id: String(params.id), title: `Workshop ${String(params.id).slice(0, 4)}` })),
    ),
  )

describe('MyRegistrationsPage', () => {
  it('lists the signed-in user\'s registrations with workshop name, ticket type and status', async () => {
    serveWorkshops()
    server.use(
      http.get('/api/v1/registrations', () =>
        HttpResponse.json(
          aRegistrationPage([
            aRegistrationSummary({ id: 'r1', workshopId: 'aaaa1111', ticketType: 'FREE', status: 'CONFIRMED' }),
            aRegistrationSummary({ id: 'r2', workshopId: 'bbbb2222', ticketType: 'PAID', status: 'EXPIRED' }),
          ]),
        ),
      ),
    )
    signInAs()

    renderRoute('/me/registrations')

    const rows = await screen.findAllByRole('listitem')
    expect(rows).toHaveLength(2)
    expect(await within(rows[0]).findByRole('link', { name: 'Workshop aaaa' })).toHaveAttribute(
      'href',
      '/workshops/aaaa1111',
    )
    expect(within(rows[0]).getByText('Miễn phí')).toBeInTheDocument()
    expect(within(rows[0]).getByText('Đã xác nhận')).toBeInTheDocument()
    expect(within(rows[1]).getByText('Có phí')).toBeInTheDocument()
    expect(within(rows[1]).getByText('Hết hạn giữ chỗ')).toBeInTheDocument()
  })

  it('offers payment only for a RESERVED paid registration', async () => {
    serveWorkshops()
    server.use(
      http.get('/api/v1/registrations', () =>
        HttpResponse.json(
          aRegistrationPage([
            aRegistrationSummary({ id: 'held', ticketType: 'PAID', status: 'RESERVED' }),
            aRegistrationSummary({ id: 'done', ticketType: 'PAID', status: 'CONFIRMED' }),
            aRegistrationSummary({ id: 'free', ticketType: 'FREE', status: 'CONFIRMED' }),
          ]),
        ),
      ),
    )
    signInAs()

    renderRoute('/me/registrations')

    const pay = await screen.findAllByRole('link', { name: 'Thanh toán' })
    expect(pay).toHaveLength(1)
    expect(pay[0]).toHaveAttribute('href', '/me/checkout/held')
  })

  it('says so when there is nothing yet', async () => {
    server.use(http.get('/api/v1/registrations', () => HttpResponse.json(aRegistrationPage([]))))
    signInAs()

    renderRoute('/me/registrations')

    expect(await screen.findByText('Bạn chưa đăng ký workshop nào.')).toBeInTheDocument()
  })

  it('asks for the page named in the URL, and pages with the buttons', async () => {
    serveWorkshops()
    const asked: string[] = []
    server.use(
      http.get('/api/v1/registrations', ({ request }) => {
        const url = new URL(request.url)
        asked.push(`${url.searchParams.get('page')}/${url.searchParams.get('size')}`)
        const page = Number(url.searchParams.get('page'))
        return HttpResponse.json({
          items: [aRegistrationSummary({ id: `r${page}` })],
          page: { page, size: 20, totalElements: 45, totalPages: 3 },
        })
      }),
    )
    signInAs()
    const user = userEvent.setup()
    const { router } = renderRoute('/me/registrations?page=1')

    expect(await screen.findByText('Trang 2 / 3')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Trang sau' }))

    expect(await screen.findByText('Trang 3 / 3')).toBeInTheDocument()
    expect(asked).toEqual(['1/20', '2/20'])
    expect(router.state.location.search).toBe('?page=2')
    expect(screen.getByRole('button', { name: 'Trang sau' })).toBeDisabled()
  })

  it('treats a nonsense page as the first page', async () => {
    const asked: (string | null)[] = []
    server.use(
      http.get('/api/v1/registrations', ({ request }) => {
        asked.push(new URL(request.url).searchParams.get('page'))
        return HttpResponse.json(aRegistrationPage([]))
      }),
    )
    signInAs()

    renderRoute('/me/registrations?page=-3')

    await screen.findByText('Bạn chưa đăng ký workshop nào.')
    expect(asked).toEqual(['0'])
  })

  it('shows an error with the correlation id when the list cannot be loaded', async () => {
    server.use(http.get('/api/v1/registrations', () => problem(500, { correlationId: 'corr-9' })))
    signInAs()

    renderRoute('/me/registrations')

    expect(await screen.findByRole('alert')).toHaveTextContent('Mã tham chiếu: corr-9')
  })

  it('sends an anonymous visitor to sign-in', async () => {
    const { router } = renderRoute('/me/registrations')

    await waitFor(() => expect(router.state.location.pathname).toBe('/login'))
    expect(router.state.location.state).toMatchObject({ from: { pathname: '/me/registrations' } })
  })

  it('a 401 ends the session and returns to sign-in (AC14.3)', async () => {
    server.use(http.get('/api/v1/registrations', () => problem(401)))
    signInAs()

    const { router } = renderRoute('/me/registrations')

    await waitFor(() => expect(router.state.location.pathname).toBe('/login'))
    expect(sessionStorage.getItem('unihub.session')).toBeNull()
  })

  it('the header shows who is signed in and signing out clears the session and the cache', async () => {
    server.use(http.get('/api/v1/registrations', () => HttpResponse.json(aRegistrationPage([]))))
    signInAs()
    const user = userEvent.setup()
    const { router, queryClient } = renderRoute('/me/registrations')

    expect(await screen.findByText(/Nguyễn Văn An \(Student\)/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Đăng xuất' }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/login'))
    expect(sessionStorage.getItem('unihub.session')).toBeNull()
    expect(queryClient.getQueryCache().getAll()).toHaveLength(0)
  })
})
