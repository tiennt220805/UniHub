import { focusManager } from '@tanstack/react-query'
import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { aAttendancePage, aWorkshopReport, anAttendance } from '@/testing/factories/report'
import { signInAs } from '@/testing/factories/session'
import { aWorkshop, WORKSHOP_ID } from '@/testing/factories/workshop'
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

// The page also reads the workshop (for its title) and the check-in list. A test that is about the figures gets a quiet pair of
// answers here and overrides one when it is about that call.
beforeEach(() => {
  server.use(
    http.get('/api/v1/workshops/:id', () => problem(404)),
    http.get('/api/v1/workshops/:id/attendances', () => HttpResponse.json(aAttendancePage([]))),
  )
})

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

describe('WorkshopReportPage: the ring and the check-in list (D164, D165)', () => {
  const serveAttendances = (respond: (page: number) => Response) => {
    const pages: number[] = []
    server.use(
      http.get('/api/v1/workshops/:id/attendances', ({ request }) => {
        const page = Number(new URL(request.url).searchParams.get('page') ?? 0)
        pages.push(page)
        return respond(page)
      }),
    )
    return pages
  }

  it('says how many check-ins out of how many confirmed registrations the ring is made of', async () => {
    server.use(http.get(URL_PATTERN, () => HttpResponse.json(aWorkshopReport({ confirmedCount: 120, checkedInCount: 86, checkInRate: 86 / 120 }))))
    asOrganizer()
    renderRoute(PATH)
    expect(await screen.findByText('86 lượt check-in / 120 đăng ký đã xác nhận')).toBeInTheDocument()
    expect(screen.getByRole('progressbar', { name: 'Tỷ lệ check-in' })).toHaveAttribute('aria-valuenow', '72')
  })

  it('lists check-ins oldest first with the time in Vietnam to the second and the first eight characters of the registration', async () => {
    server.use(http.get(URL_PATTERN, () => HttpResponse.json(aWorkshopReport())))
    serveAttendances(() =>
      HttpResponse.json(
        aAttendancePage([
          anAttendance({ registrationId: '3f9a21c4-0000-4000-8000-000000000001', checkedInAt: '2026-11-15T06:32:07Z' }),
          anAttendance({ registrationId: 'b07e5d18-0000-4000-8000-000000000002', checkedInAt: '2026-11-15T06:35:41Z' }),
        ]),
      ),
    )
    asOrganizer()
    renderRoute(PATH)

    const list = await screen.findByRole('region', { name: 'Danh sách điểm danh' })
    const rows = within(await within(list).findByRole('table')).getAllByRole('row').slice(1)
    expect(within(rows[0]).getByText('15/11/2026 13:32:07')).toBeInTheDocument()
    expect(within(rows[0]).getByText('3f9a21c4…')).toHaveAttribute('title', '3f9a21c4-0000-4000-8000-000000000001')
    expect(within(rows[1]).getByText('15/11/2026 13:35:41')).toBeInTheDocument()
    expect(list).toHaveTextContent('chưa có tên sinh viên')
  })

  it('says nobody has checked in yet', async () => {
    server.use(http.get(URL_PATTERN, () => HttpResponse.json(aWorkshopReport({ checkedInCount: 0, checkInRate: 0 }))))
    asOrganizer()
    renderRoute(PATH)
    expect(await screen.findByRole('heading', { name: 'Chưa có lượt check-in nào' })).toBeInTheDocument()
  })

  it('pages through the list and keeps the page in the URL', async () => {
    server.use(http.get(URL_PATTERN, () => HttpResponse.json(aWorkshopReport({ checkedInCount: 45 }))))
    const pages = serveAttendances((page) =>
      HttpResponse.json(
        aAttendancePage([anAttendance({ registrationId: `0000000${page}-0000-4000-8000-000000000001` })], {
          page,
          totalElements: 45,
          totalPages: 3,
        }),
      ),
    )
    asOrganizer()
    const user = userEvent.setup()
    const { router } = renderRoute(PATH)
    await screen.findByText('00000000…')

    await user.click(screen.getByRole('button', { name: 'Sau' }))

    await screen.findByText('00000001…')
    expect(pages.at(-1)).toBe(1)
    expect(router.state.location.search).toBe('?page=1')
  })

  it('shows the list\'s own error without taking the figures away', async () => {
    server.use(http.get(URL_PATTERN, () => HttpResponse.json(aWorkshopReport())))
    serveAttendances(() => problem(500, { correlationId: 'beef01' }))
    asOrganizer()
    renderRoute(PATH)

    const list = await screen.findByRole('region', { name: 'Danh sách điểm danh' })
    expect(await within(list).findByText('Không tải được danh sách điểm danh.')).toBeInTheDocument()
    expect(screen.getByLabelText('Báo cáo workshop')).toBeInTheDocument()
  })

  it('does not ask for the list when the report itself cannot be had', async () => {
    server.use(http.get(URL_PATTERN, () => problem(404)))
    const pages = serveAttendances(() => HttpResponse.json(aAttendancePage([])))
    asOrganizer()
    renderRoute(PATH)
    expect(await screen.findByRole('alert')).toHaveTextContent('Không tìm thấy workshop này.')
    expect(pages).toHaveLength(0)
  })

  it('shows the workshop title when it can be read, and the report still shows when it cannot', async () => {
    server.use(
      http.get(URL_PATTERN, () => HttpResponse.json(aWorkshopReport())),
      http.get('/api/v1/workshops/:id', () => HttpResponse.json(aWorkshop({ title: 'Nhập môn Machine Learning' }))),
    )
    signInAs({ permissions: ['REPORT_VIEW', 'WORKSHOP_EDIT'] })
    renderRoute(PATH)
    expect(await screen.findByText(/Nhập môn Machine Learning ·/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Sửa workshop' })).toHaveAttribute('href', `/admin/workshops/${WORKSHOP_ID}`)
  })

  it('offers "Sửa workshop" only with WORKSHOP_EDIT, and a missing title draws nothing', async () => {
    server.use(http.get(URL_PATTERN, () => HttpResponse.json(aWorkshopReport())))
    asOrganizer() // REPORT_VIEW only; the workshop read is answered 404 by beforeEach
    renderRoute(PATH)
    await screen.findByLabelText('Báo cáo workshop')
    expect(screen.queryByRole('link', { name: 'Sửa workshop' })).not.toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('reads the list once more when the report says the number of check-ins moved, and never polls it by itself', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    let report = aWorkshopReport({ checkedInCount: 1 })
    let listCalls = 0
    server.use(http.get(URL_PATTERN, () => HttpResponse.json(report)))
    serveAttendances(() => {
      listCalls += 1
      return HttpResponse.json(aAttendancePage([anAttendance()], { totalElements: 1, totalPages: 1 }))
    })
    asOrganizer()
    renderRoute(PATH)
    await screen.findByText('3f9a21c4…')
    expect(listCalls).toBe(1)

    // Ten seconds pass with the same count: the report is read again, the list is not.
    await act(() => vi.advanceTimersByTimeAsync(10_500))
    expect(listCalls).toBe(1)

    // The count moves: the list is read once.
    report = aWorkshopReport({ checkedInCount: 2 })
    await act(() => vi.advanceTimersByTimeAsync(10_500))
    await waitFor(() => expect(listCalls).toBe(2))
    await act(() => vi.advanceTimersByTimeAsync(1_000))
    expect(listCalls).toBe(2) // the count still differs from the list's (replica lag): no loop
  })
})
