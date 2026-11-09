import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'

import { signInAs } from '@/testing/factories/session'
import { aWorkshopPage, aWorkshopSummary } from '@/testing/factories/workshop'
import { server } from '@/testing/msw-server'
import { problem } from '@/testing/problem'
import { renderRoute } from '@/testing/render-route'

const LIST = '/api/v1/admin/workshops'
const asManager = () => signInAs({ permissions: ['WORKSHOP_CREATE', 'WORKSHOP_EDIT', 'REPORT_VIEW'] })

/** Serves GET /admin/workshops and records the query of every request, newest last. */
function serveList(respond: (query: URLSearchParams) => Response = () => HttpResponse.json(aWorkshopPage([aWorkshopSummary()]))) {
  const queries: URLSearchParams[] = []
  server.use(
    http.get(LIST, ({ request }) => {
      const query = new URL(request.url).searchParams
      queries.push(query)
      return respond(query)
    }),
  )
  return queries
}

describe('WorkshopListPage', () => {
  it('lists workshops of every status with the Master figures, a status word and an icon-free-of-colour status', async () => {
    serveList(() =>
      HttpResponse.json(
        aWorkshopPage([
          aWorkshopSummary({ id: 'd', title: 'Bản nháp A', status: 'DRAFT', capacity: 80, seatsTaken: 0 }),
          aWorkshopSummary({ id: 'p', title: 'Đã công bố B', status: 'PUBLISHED', capacity: 60, seatsTaken: 42, isPaid: true, price: 150000 }),
          aWorkshopSummary({ id: 'c', title: 'Đã hủy C', status: 'CANCELLED', capacity: 50, seatsTaken: 25 }),
        ]),
      ),
    )
    asManager()

    renderRoute('/admin/workshops')

    const table = await screen.findByRole('table', { name: 'Danh sách workshop' })
    const rows = within(table).getAllByRole('row').slice(1)
    expect(rows).toHaveLength(3)
    expect(within(rows[0]).getByText('Nháp')).toBeInTheDocument()
    expect(within(rows[0]).getByRole('link', { name: 'Sửa Bản nháp A' })).toHaveAttribute('href', '/admin/workshops/d')
    expect(within(rows[1]).getByText('Đã công bố')).toBeInTheDocument()
    expect(within(rows[1]).getByText('42 / 60')).toBeInTheDocument()
    expect(within(rows[1]).getByText('150.000 đ')).toBeInTheDocument()
    expect(within(rows[2]).getByText('Đã hủy')).toBeInTheDocument()
    // A cancelled workshop cannot be edited: the row offers to look at it.
    expect(within(rows[2]).getByRole('link', { name: 'Xem Đã hủy C' })).toBeInTheDocument()
  })

  it('marks a published workshop that has ended, by the browser clock (D158)', async () => {
    serveList(() =>
      HttpResponse.json(
        aWorkshopPage([
          aWorkshopSummary({ id: 'old', title: 'Đã qua', startTime: '2020-01-01T02:00:00Z', endTime: '2020-01-01T04:00:00Z' }),
          aWorkshopSummary({ id: 'new', title: 'Sắp tới', startTime: '2999-01-01T02:00:00Z', endTime: '2999-01-01T04:00:00Z' }),
        ]),
      ),
    )
    asManager()

    renderRoute('/admin/workshops')

    const rows = within(await screen.findByRole('table')).getAllByRole('row').slice(1)
    expect(within(rows[0]).getByText('Đã diễn ra')).toBeInTheDocument()
    expect(within(rows[1]).queryByText('Đã diễn ra')).not.toBeInTheDocument()
  })

  it('has no tab for COMPLETED: Tất cả, Nháp, Đã công bố, Đã hủy (D158)', async () => {
    serveList()
    asManager()
    renderRoute('/admin/workshops')
    await screen.findByRole('table')
    const tabs = screen.getAllByRole('tab').map((tab) => tab.textContent)
    expect(tabs).toEqual(['Tất cả', 'Nháp', 'Đã công bố', 'Đã hủy'])
  })

  it('asks for all statuses first, then for one status when a tab is chosen, and puts it in the URL', async () => {
    const queries = serveList()
    asManager()
    const user = userEvent.setup()

    const { router } = renderRoute('/admin/workshops')
    await screen.findByRole('table')
    expect(queries[0].has('status')).toBe(false)

    await user.click(screen.getByRole('tab', { name: 'Nháp' }))

    await waitFor(() => expect(queries.at(-1)?.get('status')).toBe('DRAFT'))
    expect(router.state.location.search).toBe('?status=DRAFT')
    expect(screen.getByRole('tab', { name: 'Nháp' })).toHaveAttribute('aria-selected', 'true')
  })

  it('reads the tab, the search text and the page from the URL, and ignores a status it has no tab for', async () => {
    const queries = serveList()
    asManager()

    renderRoute('/admin/workshops?status=PUBLISHED&q=aws&page=2')
    await screen.findByRole('table')
    expect(queries[0].get('status')).toBe('PUBLISHED')
    expect(queries[0].get('q')).toBe('aws')
    expect(queries[0].get('page')).toBe('2')
    expect(screen.getByRole('searchbox', { name: 'Tìm theo tên workshop' })).toHaveValue('aws')
  })

  it('treats a hand-typed COMPLETED as "all"', async () => {
    const queries = serveList()
    asManager()
    renderRoute('/admin/workshops?status=COMPLETED')
    await screen.findByRole('table')
    expect(queries[0].has('status')).toBe(false)
    expect(screen.getByRole('tab', { name: 'Tất cả' })).toHaveAttribute('aria-selected', 'true')
  })

  it('sends the search text half a second after the last key, with `replace`, and returns to the first page', async () => {
    const queries = serveList()
    asManager()
    const user = userEvent.setup()

    const { router } = renderRoute('/admin/workshops?page=1')
    await screen.findByRole('table')
    await user.type(screen.getByRole('searchbox', { name: 'Tìm theo tên workshop' }), 'cloud')

    await waitFor(() => expect(queries.at(-1)?.get('q')).toBe('cloud'), { timeout: 2000 })
    expect(queries.at(-1)?.has('page')).toBe(false)
    expect(router.state.location.search).toBe('?q=cloud')
    expect(queries.filter((q) => q.get('q') === 'cloud')).toHaveLength(1) // not one request per key
  })

  it('pages: says which rows are on screen and asks for the page the user picks', async () => {
    const queries = serveList((query) =>
      HttpResponse.json({
        items: [aWorkshopSummary({ id: 'x', title: `Trang ${query.get('page') ?? '0'}` })],
        page: { page: Number(query.get('page') ?? 0), size: 20, totalElements: 54, totalPages: 3 },
      }),
    )
    asManager()
    const user = userEvent.setup()

    renderRoute('/admin/workshops')
    await screen.findByText('Trang 0')
    expect(screen.getByText(/Dòng 1–1 trong 54 workshop/)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Sau' }))

    await screen.findByText('Trang 1')
    expect(queries.at(-1)?.get('page')).toBe('1')
  })

  it('says so when a tab has nothing, and offers to create from Nháp', async () => {
    serveList(() => HttpResponse.json(aWorkshopPage([])))
    asManager()

    renderRoute('/admin/workshops?status=DRAFT')

    expect(await screen.findByRole('heading', { name: 'Chưa có bản nháp nào' })).toBeInTheDocument()
    expect(screen.getAllByRole('link', { name: 'Tạo workshop' })[0]).toHaveAttribute('href', '/admin/workshops/new')
  })

  it('says so when a search finds nothing, and clears it', async () => {
    serveList((query) => HttpResponse.json(aWorkshopPage(query.get('q') ? [] : [aWorkshopSummary()])))
    asManager()
    const user = userEvent.setup()

    renderRoute('/admin/workshops?q=zzz')
    expect(await screen.findByRole('heading', { name: 'Không có workshop nào khớp “zzz”' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Xóa tìm kiếm' }))

    expect(await screen.findByRole('table')).toBeInTheDocument()
  })

  it('shows the error with its reference, and "Thử lại" asks again', async () => {
    let calls = 0
    serveList(() => (++calls === 1 ? problem(500, { correlationId: 'abc123' }) : HttpResponse.json(aWorkshopPage([aWorkshopSummary()]))))
    asManager()
    const user = userEvent.setup()

    renderRoute('/admin/workshops')

    expect(await screen.findByText('Không tải được danh sách workshop.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Thử lại' }))
    expect(await screen.findByRole('table')).toBeInTheDocument()
  })

  it('hides "Tạo workshop" from a session that holds WORKSHOP_EDIT but not WORKSHOP_CREATE', async () => {
    serveList()
    signInAs({ permissions: ['WORKSHOP_EDIT'] })
    renderRoute('/admin/workshops')
    await screen.findByRole('table')
    expect(screen.queryByRole('link', { name: 'Tạo workshop' })).not.toBeInTheDocument()
  })

  it('refuses a session without WORKSHOP_EDIT before any request is made', async () => {
    const queries = serveList()
    signInAs({ permissions: ['WORKSHOP_CREATE'] })
    renderRoute('/admin/workshops')
    expect(await screen.findByRole('alert')).toHaveTextContent('Bạn không có quyền truy cập trang này.')
    expect(queries).toHaveLength(0)
  })
})

describe('WorkshopListPage: the row menu (D166)', () => {
  const draft = aWorkshopSummary({ id: 'd1', title: 'Bản nháp A', status: 'DRAFT', seatsTaken: 0 })
  const live = aWorkshopSummary({ id: 'p1', title: 'Đã công bố B', status: 'PUBLISHED' })
  const gone = aWorkshopSummary({ id: 'c1', title: 'Đã hủy C', status: 'CANCELLED' })

  const open = async (user: ReturnType<typeof userEvent.setup>, title: string) =>
    user.click(await screen.findByRole('button', { name: `Thao tác với ${title}` }))

  it('offers edit, the report, publish and cancel for a draft, and publishes it after a confirmation', async () => {
    let published = 0
    const queries = serveList(() => HttpResponse.json(aWorkshopPage([draft])))
    server.use(
      http.post('/api/v1/workshops/d1/publish', () => {
        published += 1
        return HttpResponse.json({ ...draft, status: 'PUBLISHED', version: 2, hasBrochure: false })
      }),
    )
    asManager()
    const user = userEvent.setup()
    renderRoute('/admin/workshops')

    await open(user, 'Bản nháp A')
    expect(screen.getByRole('menuitem', { name: 'Sửa thông tin' })).toHaveAttribute('href', '/admin/workshops/d1')
    expect(screen.getByRole('menuitem', { name: 'Xem báo cáo' })).toHaveAttribute('href', '/admin/workshops/d1/report')
    expect(screen.getByRole('menuitem', { name: 'Hủy bản nháp' })).toBeInTheDocument()
    await user.click(screen.getByRole('menuitem', { name: 'Công bố' }))

    const dialog = await screen.findByRole('alertdialog', { name: 'Công bố workshop?' })
    expect(published).toBe(0)
    const before = queries.length
    await user.click(within(dialog).getByRole('button', { name: 'Công bố' }))

    await waitFor(() => expect(published).toBe(1))
    await waitFor(() => expect(queries.length).toBeGreaterThan(before)) // the list is read again, never patched by hand
  })

  it('offers cancel but not publish for a published workshop, and nothing that changes a cancelled one', async () => {
    serveList(() => HttpResponse.json(aWorkshopPage([live, gone])))
    asManager()
    const user = userEvent.setup()
    renderRoute('/admin/workshops')

    await open(user, 'Đã công bố B')
    expect(screen.queryByRole('menuitem', { name: 'Công bố' })).not.toBeInTheDocument()
    expect(screen.getByRole('menuitem', { name: 'Hủy workshop' })).toBeInTheDocument()
    await user.keyboard('{Escape}')

    await open(user, 'Đã hủy C')
    expect(screen.getByRole('menuitem', { name: 'Xem thông tin' })).toBeInTheDocument()
    expect(screen.queryByRole('menuitem', { name: 'Công bố' })).not.toBeInTheDocument()
    expect(screen.queryByRole('menuitem', { name: /^Hủy/ })).not.toBeInTheDocument()
  })

  it('leaves the report out of the menu without REPORT_VIEW', async () => {
    serveList(() => HttpResponse.json(aWorkshopPage([live])))
    signInAs({ permissions: ['WORKSHOP_EDIT'] })
    const user = userEvent.setup()
    renderRoute('/admin/workshops')
    await open(user, 'Đã công bố B')
    expect(screen.queryByRole('menuitem', { name: 'Xem báo cáo' })).not.toBeInTheDocument()
  })

  it('cancels a published workshop with the honest confirmation', async () => {
    let cancelled = 0
    serveList(() => HttpResponse.json(aWorkshopPage([live])))
    server.use(
      http.post('/api/v1/workshops/p1/cancel', () => {
        cancelled += 1
        return HttpResponse.json({ ...live, status: 'CANCELLED' })
      }),
    )
    asManager()
    const user = userEvent.setup()
    renderRoute('/admin/workshops')

    await open(user, 'Đã công bố B')
    await user.click(screen.getByRole('menuitem', { name: 'Hủy workshop' }))
    const dialog = await screen.findByRole('alertdialog', { name: 'Hủy workshop này?' })
    expect(dialog).toHaveTextContent('Đăng ký hiện có không bị hủy tự động.')
    await user.click(within(dialog).getByRole('button', { name: 'Hủy workshop' }))

    await waitFor(() => expect(cancelled).toBe(1))
  })
})

describe('AdminIndexPage (/admin)', () => {
  it('goes to the list for WORKSHOP_EDIT', async () => {
    serveList()
    asManager()
    const { router } = renderRoute('/admin')
    await screen.findByRole('table')
    expect(router.state.location.pathname).toBe('/admin/workshops')
  })

  it('goes to the create page for WORKSHOP_CREATE alone', async () => {
    signInAs({ permissions: ['WORKSHOP_CREATE'] })
    const { router } = renderRoute('/admin')
    await waitFor(() => expect(router.state.location.pathname).toBe('/admin/workshops/new'))
  })

  it('tells a report-only session how a report is reached', async () => {
    signInAs({ permissions: ['REPORT_VIEW'] })
    renderRoute('/admin')
    expect(await screen.findByRole('heading', { name: 'Tài khoản của bạn chỉ xem được báo cáo' })).toBeInTheDocument()
  })
})
