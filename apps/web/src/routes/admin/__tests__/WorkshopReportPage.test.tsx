import { focusManager } from '@tanstack/react-query'
import { act, screen, waitFor, within } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { aWorkshopReport } from '@/testing/factories/report'
import { signInAs } from '@/testing/factories/session'
import { WORKSHOP_ID } from '@/testing/factories/workshop'
import { server } from '@/testing/msw-server'
import { problem } from '@/testing/problem'
import { renderRoute } from '@/testing/render-route'

const PATH = `/admin/workshops/${WORKSHOP_ID}/report`
const URL_PATTERN = '/api/v1/workshops/:id/report'

afterEach(() => {
  focusManager.setFocused(undefined)
  vi.useRealTimers()
})

const asOrganizer = () => signInAs({ permissions: ['REPORT_VIEW'] })

function figure(label: string) {
  return screen.getByText(label).closest('div') as HTMLElement
}

describe('WorkshopReportPage', () => {
  it('shows the figures the Backend counted, as they are', async () => {
    server.use(http.get(URL_PATTERN, () => HttpResponse.json(aWorkshopReport())))
    asOrganizer()

    renderRoute(PATH)

    expect(await screen.findByRole('heading', { name: 'Báo cáo workshop' })).toBeInTheDocument()
    await screen.findByLabelText('Báo cáo workshop')
    expect(within(figure('Sức chứa')).getByText('40')).toBeInTheDocument()
    expect(within(figure('Chỗ đã giữ')).getByText('4')).toBeInTheDocument()
    expect(within(figure('Đăng ký đã xác nhận')).getByText('3')).toBeInTheDocument()
    expect(within(figure('Đang giữ chỗ (chưa thanh toán)')).getByText('1')).toBeInTheDocument()
    expect(within(figure('Đã check-in')).getByText('2')).toBeInTheDocument()
    expect(screen.getByText('67%')).toBeInTheDocument()
    expect(screen.getByRole('progressbar', { name: 'Tỷ lệ check-in' })).toHaveAttribute(
      'aria-valuenow',
      '67',
    )
  })

  it('asks for the report of the workshop in the URL, with the session token', async () => {
    const asked: { id: string; auth: string | null }[] = []
    server.use(
      http.get(URL_PATTERN, ({ params, request }) => {
        asked.push({ id: String(params.id), auth: request.headers.get('Authorization') })
        return HttpResponse.json(aWorkshopReport())
      }),
    )
    asOrganizer()

    renderRoute(PATH)

    await screen.findByLabelText('Báo cáo workshop')
    expect(asked).toEqual([{ id: WORKSHOP_ID, auth: 'Bearer opaque-access-token' }])
  })

  it('shows "no data" for a null rate, not 0%', async () => {
    server.use(
      http.get(URL_PATTERN, () =>
        HttpResponse.json(aWorkshopReport({ confirmedCount: 0, checkedInCount: 0, checkInRate: null })),
      ),
    )
    asOrganizer()

    renderRoute(PATH)

    expect(await screen.findByText(/Chưa có dữ liệu/)).toBeInTheDocument()
    expect(screen.queryByText('0%')).not.toBeInTheDocument()
    expect(screen.queryByRole('progressbar')).not.toBeInTheDocument()
  })

  it('shows a real 0% when there are confirmed registrations and nobody came', async () => {
    server.use(
      http.get(URL_PATTERN, () =>
        HttpResponse.json(aWorkshopReport({ checkedInCount: 0, checkInRate: 0 })),
      ),
    )
    asOrganizer()

    renderRoute(PATH)

    expect(await screen.findByText('0%')).toBeInTheDocument()
    expect(screen.queryByText(/Chưa có dữ liệu/)).not.toBeInTheDocument()
  })

  it('caps the bar at full above 100% but still shows the real number and why', async () => {
    server.use(
      http.get(URL_PATTERN, () =>
        HttpResponse.json(aWorkshopReport({ confirmedCount: 1, checkedInCount: 2, checkInRate: 2 })),
      ),
    )
    asOrganizer()

    renderRoute(PATH)

    expect(await screen.findByText('200%')).toBeInTheDocument()
    expect(screen.getByRole('progressbar', { name: 'Tỷ lệ check-in' })).toHaveAttribute(
      'aria-valuenow',
      '100',
    )
    expect(screen.getByText(/bị hủy sau khi đã check-in/)).toBeInTheDocument()
  })

  it('shows a loading state until the report arrives', async () => {
    server.use(
      http.get(URL_PATTERN, async () => {
        await new Promise((resolve) => setTimeout(resolve, 50))
        return HttpResponse.json(aWorkshopReport())
      }),
    )
    asOrganizer()

    renderRoute(PATH)

    expect(screen.getByText('Đang tải…')).toBeInTheDocument()
    expect(await screen.findByLabelText('Báo cáo workshop')).toBeInTheDocument()
  })

  it('says the workshop was not found on a 404', async () => {
    server.use(http.get(URL_PATTERN, () => problem(404, { correlationId: 'corr-4' })))
    asOrganizer()

    renderRoute(PATH)

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Không tìm thấy workshop này.')
    expect(alert).toHaveTextContent('Mã tham chiếu: corr-4')
  })

  it('shows an error with the correlation id when the report cannot be loaded', async () => {
    server.use(http.get(URL_PATTERN, () => problem(500, { correlationId: 'corr-7' })))
    asOrganizer()

    renderRoute(PATH)

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Không tải được báo cáo.')
    expect(alert).toHaveTextContent('Mã tham chiếu: corr-7')
  })

  it('says so on a 403 from the Backend, whatever the guard let through', async () => {
    server.use(http.get(URL_PATTERN, () => problem(403)))
    asOrganizer()

    renderRoute(PATH)

    expect(await screen.findByRole('alert')).toHaveTextContent('Bạn không có quyền xem báo cáo này.')
  })

  it('a 401 ends the session and returns to sign-in', async () => {
    server.use(http.get(URL_PATTERN, () => problem(401)))
    asOrganizer()

    const { router } = renderRoute(PATH)

    await waitFor(() => expect(router.state.location.pathname).toBe('/login'))
    expect(sessionStorage.getItem('unihub.session')).toBeNull()
  })

  // ---------- the guard (UX only; the Backend enforces) ----------

  it('without REPORT_VIEW the page is not shown and the report is never asked for', async () => {
    let asked = 0
    server.use(
      http.get(URL_PATTERN, () => {
        asked += 1
        return HttpResponse.json(aWorkshopReport())
      }),
    )
    signInAs({ permissions: ['REGISTRATION_CREATE', 'WORKSHOP_EDIT'] })

    renderRoute(PATH)

    expect(await screen.findByRole('alert')).toHaveTextContent('Bạn không có quyền truy cập trang này.')
    expect(asked).toBe(0)
  })

  it('an anonymous visitor is sent to sign-in', async () => {
    const { router } = renderRoute(PATH)

    await waitFor(() => expect(router.state.location.pathname).toBe('/login'))
    expect(router.state.location.state).toMatchObject({ from: { pathname: PATH } })
  })

  // ---------- keeping the figures fresh ----------

  it('asks again every ten seconds while the tab is visible', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    let calls = 0
    server.use(
      http.get(URL_PATTERN, () => {
        calls += 1
        return HttpResponse.json(aWorkshopReport({ checkedInCount: calls }))
      }),
    )
    asOrganizer()
    renderRoute(PATH)
    await screen.findByLabelText('Báo cáo workshop')
    expect(calls).toBe(1)

    await act(() => vi.advanceTimersByTimeAsync(10_000))

    await waitFor(() => expect(calls).toBe(2))
    await waitFor(() => expect(within(figure('Đã check-in')).getByText('2')).toBeInTheDocument())
  })

  it('does not ask on the interval while the tab is hidden, and asks again when it is shown', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    let calls = 0
    server.use(
      http.get(URL_PATTERN, () => {
        calls += 1
        return HttpResponse.json(aWorkshopReport())
      }),
    )
    asOrganizer()
    renderRoute(PATH)
    await screen.findByLabelText('Báo cáo workshop')
    expect(calls).toBe(1)

    act(() => focusManager.setFocused(false))
    await act(() => vi.advanceTimersByTimeAsync(60_000))
    expect(calls).toBe(1)

    act(() => focusManager.setFocused(true))
    await waitFor(() => expect(calls).toBe(2))
  })

  it('keeps showing the last figures when a refresh fails', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    let calls = 0
    server.use(
      http.get(URL_PATTERN, () => {
        calls += 1
        return calls === 1 ? HttpResponse.json(aWorkshopReport()) : problem(500)
      }),
    )
    asOrganizer()
    renderRoute(PATH)
    await screen.findByLabelText('Báo cáo workshop')

    await act(() => vi.advanceTimersByTimeAsync(10_000))

    await waitFor(() => expect(calls).toBeGreaterThanOrEqual(2))
    expect(screen.getByLabelText('Báo cáo workshop')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})
