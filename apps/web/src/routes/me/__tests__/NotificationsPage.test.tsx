import { focusManager } from '@tanstack/react-query'
import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { afterEach, describe, expect, it } from 'vitest'

import { aNotification, aNotificationPage } from '@/testing/factories/notification'
import { signInAs } from '@/testing/factories/session'
import { server } from '@/testing/msw-server'
import { problem } from '@/testing/problem'
import { renderRoute } from '@/testing/render-route'

afterEach(() => focusManager.setFocused(undefined))

describe('NotificationsPage', () => {
  it('lists the notifications as the Backend rendered them, in the order it returned them', async () => {
    server.use(
      http.get('/api/v1/notifications', () =>
        HttpResponse.json(
          aNotificationPage([
            aNotification({
              id: 'n2',
              type: 'WORKSHOP_RESCHEDULED',
              title: 'Workshop đổi lịch: Kỹ năng thuyết trình',
              body: 'Workshop "Kỹ năng thuyết trình" đã đổi từ 01/11 sang 02/11 tại A1.',
              createdAt: '2026-10-27T02:00:00Z',
            }),
            aNotification({ id: 'n1', title: 'Đăng ký thành công: Kỹ năng thuyết trình' }),
          ]),
        ),
      ),
    )
    signInAs()

    renderRoute('/me/notifications')

    const rows = await screen.findAllByRole('listitem')
    expect(rows).toHaveLength(2)
    expect(within(rows[0]).getByRole('heading', { name: 'Workshop đổi lịch: Kỹ năng thuyết trình' })).toBeInTheDocument()
    expect(within(rows[0]).getByText(/đã đổi từ 01\/11 sang 02\/11 tại A1\./)).toBeInTheDocument()
    expect(within(rows[0]).getByText('09:00 27 thg 10, 2026')).toBeInTheDocument()
    expect(within(rows[1]).getByRole('heading', { name: 'Đăng ký thành công: Kỹ năng thuyết trình' })).toBeInTheDocument()
  })

  it('shows the text as text: markup in a title or body is never interpreted', async () => {
    server.use(
      http.get('/api/v1/notifications', () =>
        HttpResponse.json(
          aNotificationPage([aNotification({ title: '<b>Đậm</b>', body: '<img src=x onerror=alert(1)>' })]),
        ),
      ),
    )
    signInAs()

    renderRoute('/me/notifications')

    expect(await screen.findByText('<b>Đậm</b>')).toBeInTheDocument()
    expect(screen.getByText('<img src=x onerror=alert(1)>')).toBeInTheDocument()
    expect(document.querySelector('main img, main b')).toBeNull()
  })

  it('says so when there is nothing yet', async () => {
    server.use(http.get('/api/v1/notifications', () => HttpResponse.json(aNotificationPage([]))))
    signInAs()

    renderRoute('/me/notifications')

    expect(await screen.findByText('Bạn chưa có thông báo nào.')).toBeInTheDocument()
    expect(screen.queryByRole('navigation', { name: 'Phân trang' })).not.toBeInTheDocument()
  })

  it('shows a loading state until the list arrives', async () => {
    server.use(
      http.get('/api/v1/notifications', async () => {
        await new Promise((resolve) => setTimeout(resolve, 50))
        return HttpResponse.json(aNotificationPage([aNotification()]))
      }),
    )
    signInAs()

    renderRoute('/me/notifications')

    expect(screen.getByText('Đang tải…')).toBeInTheDocument()
    expect(await screen.findAllByRole('listitem')).toHaveLength(1)
  })

  it('shows an error with the correlation id when the list cannot be loaded', async () => {
    server.use(http.get('/api/v1/notifications', () => problem(500, { correlationId: 'corr-7' })))
    signInAs()

    renderRoute('/me/notifications')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Không tải được danh sách thông báo.')
    expect(alert).toHaveTextContent('Mã tham chiếu: corr-7')
  })

  it('asks the user to slow down on a 429', async () => {
    server.use(http.get('/api/v1/notifications', () => problem(429)))
    signInAs()

    renderRoute('/me/notifications')

    expect(await screen.findByRole('alert')).toHaveTextContent('Bạn đang gửi quá nhiều yêu cầu.')
  })

  it('asks for the page named in the URL, and pages with the buttons', async () => {
    const asked: string[] = []
    server.use(
      http.get('/api/v1/notifications', ({ request }) => {
        const url = new URL(request.url)
        asked.push(`${url.searchParams.get('page')}/${url.searchParams.get('size')}`)
        const page = Number(url.searchParams.get('page'))
        return HttpResponse.json({
          items: [aNotification({ id: `n${page}`, title: `Thông báo ${page}` })],
          page: { page, size: 20, totalElements: 45, totalPages: 3 },
        })
      }),
    )
    signInAs()
    const user = userEvent.setup()
    const { router } = renderRoute('/me/notifications?page=1')

    expect(await screen.findByText('Trang 2 / 3')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Trang sau' }))

    expect(await screen.findByText('Trang 3 / 3')).toBeInTheDocument()
    expect(asked).toEqual(['1/20', '2/20'])
    expect(router.state.location.search).toBe('?page=2')
    expect(screen.getByRole('button', { name: 'Trang sau' })).toBeDisabled()

    await user.click(screen.getByRole('button', { name: 'Trang trước' }))
    await user.click(screen.getByRole('button', { name: 'Trang trước' }))
    expect(await screen.findByText('Trang 1 / 3')).toBeInTheDocument()
    expect(router.state.location.search).toBe('')
    expect(screen.getByRole('button', { name: 'Trang trước' })).toBeDisabled()
  })

  it('treats a nonsense page as the first page', async () => {
    const asked: (string | null)[] = []
    server.use(
      http.get('/api/v1/notifications', ({ request }) => {
        asked.push(new URL(request.url).searchParams.get('page'))
        return HttpResponse.json(aNotificationPage([]))
      }),
    )
    signInAs()

    renderRoute('/me/notifications?page=-3')

    await screen.findByText('Bạn chưa có thông báo nào.')
    expect(asked).toEqual(['0'])
  })

  it('asks again when the tab regains focus, and shows what arrived meanwhile', async () => {
    let calls = 0
    server.use(
      http.get('/api/v1/notifications', () => {
        calls += 1
        return HttpResponse.json(
          aNotificationPage(
            calls === 1
              ? [aNotification({ id: 'n1', title: 'Đăng ký thành công: A' })]
              : [
                  aNotification({ id: 'n2', title: 'Workshop đã bị hủy: A' }),
                  aNotification({ id: 'n1', title: 'Đăng ký thành công: A' }),
                ],
          ),
        )
      }),
    )
    signInAs()
    renderRoute('/me/notifications')
    expect(await screen.findAllByRole('listitem')).toHaveLength(1)

    act(() => focusManager.setFocused(false))
    act(() => focusManager.setFocused(true))

    await waitFor(() => expect(screen.getAllByRole('listitem')).toHaveLength(2))
    expect(calls).toBe(2)
    expect(screen.getAllByRole('listitem')[0]).toHaveTextContent('Workshop đã bị hủy: A')
  })

  it('offers no unread state and no way to mark anything as read', async () => {
    server.use(
      http.get('/api/v1/notifications', () => HttpResponse.json(aNotificationPage([aNotification()]))),
    )
    signInAs()

    renderRoute('/me/notifications')

    await screen.findAllByRole('listitem')
    expect(within(screen.getByRole('main')).queryByRole('button')).not.toBeInTheDocument()
    expect(screen.queryByText(/chưa đọc|đánh dấu/i)).not.toBeInTheDocument()
  })

  it('sends an anonymous visitor to sign-in', async () => {
    const { router } = renderRoute('/me/notifications')

    await waitFor(() => expect(router.state.location.pathname).toBe('/login'))
    expect(router.state.location.state).toMatchObject({ from: { pathname: '/me/notifications' } })
  })

  it('a 401 ends the session and returns to sign-in (AC14.3)', async () => {
    server.use(http.get('/api/v1/notifications', () => problem(401)))
    signInAs()

    const { router } = renderRoute('/me/notifications')

    await waitFor(() => expect(router.state.location.pathname).toBe('/login'))
    expect(sessionStorage.getItem('unihub.session')).toBeNull()
  })

  it('the header links to the inbox only while signed in', async () => {
    renderRoute('/workshops')

    await screen.findByRole('link', { name: 'Đăng nhập' })
    expect(screen.queryByRole('link', { name: 'Thông báo' })).not.toBeInTheDocument()
  })

  it('the header link opens the inbox', async () => {
    server.use(
      http.get('/api/v1/workshops', () =>
        HttpResponse.json({ items: [], page: { page: 0, size: 20, totalElements: 0, totalPages: 0 } }),
      ),
      http.get('/api/v1/notifications', () => HttpResponse.json(aNotificationPage([aNotification()]))),
    )
    signInAs()
    const user = userEvent.setup()
    const { router } = renderRoute('/workshops')

    await user.click(await screen.findByRole('link', { name: 'Thông báo' }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/me/notifications'))
    expect(await screen.findAllByRole('listitem')).toHaveLength(1)
  })
})
