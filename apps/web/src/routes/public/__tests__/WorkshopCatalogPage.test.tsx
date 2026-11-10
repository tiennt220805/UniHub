import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it, vi } from 'vitest'

import { SEARCH_DEBOUNCE_MS } from '@/shared/hooks/use-draft'
import { endOfDay, startOfDay, ymdOf } from '@/shared/lib/vn-time'
import { aWorkshop, aWorkshopPage, aWorkshopSummary, WORKSHOP_ID } from '@/testing/factories/workshop'
import { server } from '@/testing/msw-server'
import { renderRoute } from '@/testing/render-route'

const TODAY = ymdOf(new Date())

/** Serves GET /workshops and records the query of every request, newest last. */
function serveList(respond: (query: URLSearchParams) => Response = () => HttpResponse.json(aWorkshopPage([aWorkshopSummary()]))) {
  const queries: URLSearchParams[] = []
  server.use(
    http.get('/api/v1/workshops', ({ request }) => {
      const query = new URL(request.url).searchParams
      queries.push(query)
      return respond(query)
    }),
  )
  return queries
}

const problem = (status: number, extra: Record<string, unknown> = {}, headers: Record<string, string> = {}) =>
  HttpResponse.json(
    { type: 'about:blank', title: 'Problem', status, ...extra },
    { status, headers: { 'Content-Type': 'application/problem+json', ...headers } },
  )

describe('WorkshopCatalogPage: the cards', () => {
  it('renders each workshop as a card from the list response alone: title, time, speaker, place, price, seat level', async () => {
    serveList(() =>
      HttpResponse.json(
        aWorkshopPage([
          aWorkshopSummary({ isPaid: true, price: 150000, capacity: 60, seatsTaken: 10 }),
          aWorkshopSummary({ id: 'b', title: 'Free one', speakerName: 'Ms. Free', location: 'Hall B', capacity: 60, seatsTaken: 55 }),
          aWorkshopSummary({ id: 'c', title: 'Full one', capacity: 60, seatsTaken: 60 }),
        ]),
      ),
    )

    renderRoute('/workshops')

    const paid = (await screen.findByRole('link', { name: 'Intro to Distributed Locks' })).closest('li')!
    expect(paid).toHaveTextContent('Dr. Lan Nguyen')
    expect(paid).toHaveTextContent('Hall A')
    // 02:00Z and 04:00Z, rendered in UniHub's operating zone, Asia/Ho_Chi_Minh (UTC+7).
    expect(paid).toHaveTextContent('09:00 – 11:00')
    expect(paid).toHaveTextContent('Có phí · 150.000 đ')
    expect(paid).toHaveTextContent('Còn chỗ')
    const low = screen.getByRole('link', { name: 'Free one' }).closest('li')!
    expect(low).toHaveTextContent('Miễn phí')
    expect(low).toHaveTextContent('Sắp hết')
    expect(low).toHaveTextContent('Ms. Free')
    expect(screen.getByRole('link', { name: 'Full one' }).closest('li')).toHaveTextContent('Hết chỗ')
  })

  it('never asks for a workshop detail to draw a card (D117)', async () => {
    serveList()
    let detailReads = 0
    server.use(http.get('/api/v1/workshops/:id', () => { detailReads += 1; return HttpResponse.json(aWorkshop()) }))

    renderRoute('/workshops')
    await screen.findByRole('link', { name: 'Intro to Distributed Locks' })

    expect(detailReads).toBe(0)
  })

  it('draws a generated cover for a workshop without an image, and the image when it has one (D123)', async () => {
    serveList(() =>
      HttpResponse.json(
        aWorkshopPage([
          aWorkshopSummary({ id: 'a', title: 'No cover' }),
          aWorkshopSummary({ id: 'b', title: 'With cover', coverImageUrl: '/media/covers/x.png' }),
        ]),
      ),
    )

    renderRoute('/workshops')

    const bare = (await screen.findByRole('link', { name: 'No cover' })).closest('li')!
    expect(bare.querySelector('svg[viewBox="0 0 160 90"]')).not.toBeNull()
    expect(bare.querySelector('img')).toBeNull()
    const photo = screen.getByRole('link', { name: 'With cover' }).closest('li')!
    expect(photo.querySelector('img')).toHaveAttribute('src', '/media/covers/x.png')
    // The generated cover uses presentation attributes only: no <style> element and no style="" attribute (CSP).
    expect(bare.querySelector('svg style')).toBeNull()
    expect(bare.querySelector('svg [style]')).toBeNull()
  })

  it('links each card to its detail page, and the whole card is one link', async () => {
    serveList()

    renderRoute('/workshops')

    const link = await screen.findByRole('link', { name: 'Intro to Distributed Locks' })
    expect(link).toHaveAttribute('href', `/workshops/${WORKSHOP_ID}`)
    expect(within(link.closest('li')!).getAllByRole('link')).toHaveLength(1)
  })

  it('navigates from the list to the detail page on click', async () => {
    serveList()
    server.use(http.get(`/api/v1/workshops/${WORKSHOP_ID}`, () => HttpResponse.json(aWorkshop())))

    renderRoute('/workshops')
    await userEvent.click(await screen.findByRole('link', { name: 'Intro to Distributed Locks' }))

    expect(await screen.findByText('Dr. Lan Nguyen')).toBeInTheDocument()
  })

  it('groups the cards by day in Vietnam, one heading per day (D140)', async () => {
    serveList(() =>
      HttpResponse.json(
        aWorkshopPage([
          aWorkshopSummary({ id: 'a', title: 'Morning', startTime: '2026-11-11T02:00:00Z', endTime: '2026-11-11T04:00:00Z' }),
          aWorkshopSummary({ id: 'b', title: 'Afternoon', startTime: '2026-11-11T07:00:00Z', endTime: '2026-11-11T09:00:00Z' }),
          // 18:30Z is 01:30 the next day in Vietnam
          aWorkshopSummary({ id: 'c', title: 'Past midnight', startTime: '2026-11-11T18:30:00Z', endTime: '2026-11-11T20:00:00Z' }),
        ]),
      ),
    )

    renderRoute('/workshops')

    const wednesday = (await screen.findByRole('heading', { name: 'Thứ Tư, 11/11/2026' })).closest('section')!
    expect(within(wednesday).getAllByRole('listitem')).toHaveLength(2)
    expect(wednesday).toHaveTextContent('2 workshop')
    const thursday = screen.getByRole('heading', { name: 'Thứ Năm, 12/11/2026' }).closest('section')!
    expect(within(thursday).getByRole('link', { name: 'Past midnight' })).toBeInTheDocument()
  })
})

describe('WorkshopCatalogPage: the request (D138, D139)', () => {
  it('starts at the beginning of today in Vietnam when the URL has no dateFrom, and sends nothing else', async () => {
    const queries = serveList()

    renderRoute('/workshops')
    await screen.findByRole('link', { name: 'Intro to Distributed Locks' })

    expect(queries).toHaveLength(1)
    expect(Object.fromEntries(queries[0])).toEqual({ dateFrom: startOfDay(TODAY) })
  })

  it('turns the filters in the URL into the query: dateTo at the end of its day, page as given', async () => {
    const queries = serveList()

    renderRoute('/workshops?q=cloud&speaker=An&location=A2&dateFrom=2026-10-01&dateTo=2026-10-31&page=1')
    await screen.findByRole('link', { name: 'Intro to Distributed Locks' }).catch(() => undefined)
    await waitFor(() => expect(queries.length).toBeGreaterThan(0))

    expect(Object.fromEntries(queries[0])).toEqual({
      q: 'cloud',
      speaker: 'An',
      location: 'A2',
      dateFrom: startOfDay('2026-10-01'),
      dateTo: endOfDay('2026-10-31'),
      page: '1',
    })
  })

  it('shows the filters of the URL in the form, and the default start date is today', async () => {
    serveList()

    renderRoute('/workshops?q=cloud&dateTo=2026-12-31')

    expect(screen.getByLabelText('Chủ đề')).toHaveValue('cloud')
    expect(screen.getByLabelText('Từ ngày')).toHaveValue(TODAY)
    expect(screen.getByLabelText('Đến ngày')).toHaveValue('2026-12-31')
    await screen.findByRole('link', { name: 'Intro to Distributed Locks' })
    expect(screen.getByRole('status')).toHaveTextContent('1 workshop')
  })
})

describe('WorkshopCatalogPage: filtering from the form', () => {
  it('applies a typed topic after a pause, in the URL and in the request, with replace (no history entry per key)', async () => {
    const queries = serveList()
    const user = userEvent.setup()
    const { router } = renderRoute('/workshops')
    await screen.findByRole('link', { name: 'Intro to Distributed Locks' })
    const before = router.state.historyAction

    await user.type(screen.getByLabelText('Chủ đề'), 'cloud')

    await waitFor(() => expect(router.state.location.search).toBe('?q=cloud'), { timeout: 3000 })
    await waitFor(() => expect(queries.at(-1)?.get('q')).toBe('cloud'))
    expect(router.state.historyAction).not.toBe('PUSH')
    expect(before).toBe('POP')
    // One request for the first load and one for the finished word: not one per key.
    expect(queries).toHaveLength(2)
  })

  it('keeps a space typed between two words', async () => {
    serveList()
    const user = userEvent.setup()
    const { router } = renderRoute('/workshops')
    await screen.findByRole('link', { name: 'Intro to Distributed Locks' })

    await user.type(screen.getByLabelText('Chủ đề'), 'cloud ')
    await waitFor(() => expect(router.state.location.search).toBe('?q=cloud'), { timeout: 3000 })
    await user.type(screen.getByLabelText('Chủ đề'), 'native')

    await waitFor(() => expect(router.state.location.search).toBe('?q=cloud+native'), { timeout: 3000 })
  })

  it('applies a date at once and pushes a history entry; changing a filter goes back to the first page', async () => {
    serveList()
    const user = userEvent.setup()
    const { router } = renderRoute('/workshops?page=2&q=cloud')
    await screen.findByLabelText('Chủ đề')

    await user.clear(screen.getByLabelText('Đến ngày'))
    await user.type(screen.getByLabelText('Đến ngày'), '2026-12-31')

    await waitFor(() => expect(router.state.location.search).toBe('?q=cloud&dateTo=2026-12-31'))
    expect(router.state.historyAction).toBe('PUSH')
  })

  it('goes to another page through the pagination, keeping the filters', async () => {
    const queries = serveList(() =>
      HttpResponse.json({ items: [aWorkshopSummary()], page: { page: 0, size: 20, totalElements: 45, totalPages: 3 } }),
    )
    const user = userEvent.setup()
    const { router } = renderRoute('/workshops?q=cloud')
    await screen.findByRole('link', { name: 'Intro to Distributed Locks' })
    expect(screen.getByRole('status')).toHaveTextContent('45 workshop')
    expect(screen.getByRole('status')).toHaveTextContent('Trang 1/3')

    await user.click(screen.getByRole('button', { name: 'Trang 2' }))

    await waitFor(() => expect(router.state.location.search).toBe('?q=cloud&page=1'))
    await waitFor(() => expect(queries.at(-1)?.get('page')).toBe('1'))
  })

  it('clears every filter, and offers that only while one is set', async () => {
    serveList()
    const user = userEvent.setup()
    const { router } = renderRoute('/workshops?q=cloud&speaker=An')
    await screen.findByLabelText('Chủ đề')

    await user.click(screen.getByRole('button', { name: 'Xóa bộ lọc' }))

    await waitFor(() => expect(router.state.location.search).toBe(''))
    expect(screen.queryByRole('button', { name: 'Xóa bộ lọc' })).not.toBeInTheDocument()
  })

  it('keeps both edits when two text filters change inside one debounce window', async () => {
    // Found on the cluster run (Phase 8 task 9.3): clearing "Diễn giả" and filling "Địa điểm" at once (Playwright's `fill`)
    // made both timers fire from one stale copy of the URL; the second write put the cleared speaker back, and the box
    // stayed empty while the old filter kept applying. Fake timers fire both in one go, as they did there.
    serveList()
    const { router } = renderRoute('/workshops?speaker=Lan')
    await screen.findByLabelText('Chủ đề')

    vi.useFakeTimers({ shouldAdvanceTime: false, toFake: ['setTimeout', 'clearTimeout'] })
    try {
      fireEvent.change(screen.getByLabelText('Diễn giả'), { target: { value: '' } })
      fireEvent.change(screen.getByLabelText('Địa điểm'), { target: { value: 'B2' } })
      act(() => {
        vi.advanceTimersByTime(SEARCH_DEBOUNCE_MS + 10)
      })
    } finally {
      vi.useRealTimers()
    }

    await waitFor(() => expect(router.state.location.search).toBe('?location=B2'))
    expect(screen.getByLabelText('Diễn giả')).toHaveValue('')
  })

  it('follows the URL when it changes under the form (Back)', async () => {
    serveList()
    const user = userEvent.setup()
    const { router } = renderRoute('/workshops?q=cloud')
    await screen.findByLabelText('Chủ đề')

    await user.click(screen.getByRole('button', { name: 'Xóa bộ lọc' }))

    await waitFor(() => expect(screen.getByLabelText('Chủ đề')).toHaveValue(''))
    await router.navigate(-1)
    await waitFor(() => expect(screen.getByLabelText('Chủ đề')).toHaveValue('cloud'))
  })
})

describe('WorkshopCatalogPage: a backwards date range', () => {
  it('says so next to the dates and sends no request for it', async () => {
    const queries = serveList()

    renderRoute('/workshops?dateFrom=2026-11-30&dateTo=2026-11-09')

    expect(await screen.findByText(/phải trước hoặc bằng/)).toBeInTheDocument()
    expect(screen.getByLabelText('Từ ngày')).toHaveAttribute('aria-invalid', 'true')
    // (a request still in flight from the previous test may land here; only one for this range would be a failure)
    expect(queries.filter((q) => q.get('dateFrom') === startOfDay('2026-11-30'))).toHaveLength(0)
  })

  it('shows the same message when the server answers 400 for a range the client let through', async () => {
    serveList(() => problem(400))

    renderRoute('/workshops?dateTo=2026-11-09')

    expect(await screen.findByRole('alert')).toHaveTextContent('phải trước hoặc bằng')
  })
})

describe('WorkshopCatalogPage: empty, loading and failing', () => {
  it('says there is nothing coming when there are no items and no filter', async () => {
    serveList(() => HttpResponse.json(aWorkshopPage([])))

    renderRoute('/workshops')

    expect(await screen.findByText('Chưa có workshop sắp diễn ra')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Xóa bộ lọc' })).not.toBeInTheDocument()
  })

  it('says nothing matched, and offers to clear, when a filter is set', async () => {
    serveList(() => HttpResponse.json(aWorkshopPage([])))
    const user = userEvent.setup()
    const { router } = renderRoute('/workshops?q=blockchain')

    expect(await screen.findByText('Không có workshop phù hợp')).toBeInTheDocument()
    expect(screen.getByText(/Chủ đề "blockchain"/)).toBeInTheDocument()
    await user.click(screen.getAllByRole('button', { name: 'Xóa bộ lọc' })[0])

    await waitFor(() => expect(router.state.location.search).toBe(''))
  })

  it('says a page past the end has no content, and goes back to the first', async () => {
    serveList(() => HttpResponse.json({ items: [], page: { page: 9, size: 20, totalElements: 45, totalPages: 3 } }))
    const user = userEvent.setup()
    const { router } = renderRoute('/workshops?page=9')

    expect(await screen.findByText('Trang này không còn nội dung')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Về trang 1' }))

    await waitFor(() => expect(router.state.location.search).toBe(''))
  })

  it('shows a loading state with a status line while the first page is on its way', async () => {
    server.use(http.get('/api/v1/workshops', async () => { await new Promise((r) => setTimeout(r, 200)); return HttpResponse.json(aWorkshopPage([aWorkshopSummary()])) }))

    renderRoute('/workshops')

    expect(screen.getByRole('status')).toHaveTextContent('Đang tải danh sách workshop')
    expect(await screen.findByRole('link', { name: 'Intro to Distributed Locks' })).toBeInTheDocument()
  })

  it('shows an error with the correlation id and a retry that asks again', async () => {
    let calls = 0
    serveList(() => {
      calls += 1
      return calls === 1 ? problem(500, { correlationId: '4bf92f3577b34da6a3ce929d0e0e4736' }) : HttpResponse.json(aWorkshopPage([aWorkshopSummary()]))
    })
    const user = userEvent.setup()

    renderRoute('/workshops')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Không tải được danh sách workshop.')
    expect(alert).toHaveTextContent('Mã tham chiếu: 4bf92f3577b34da6a3ce929d0e0e4736')
    await user.click(within(alert).getByRole('button', { name: 'Thử lại' }))
    expect(await screen.findByRole('link', { name: 'Intro to Distributed Locks' })).toBeInTheDocument()
  })

  it('tells the user when to retry after a 429, with no retry button', async () => {
    serveList(() => problem(429, {}, { 'Retry-After': '30' }))

    renderRoute('/workshops')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Vui lòng thử lại sau 30 giây.')
    expect(within(alert).queryByRole('button')).not.toBeInTheDocument()
  })

  it('says the connection is lost when the request does not get an answer', async () => {
    server.use(http.get('/api/v1/workshops', () => HttpResponse.error()))

    renderRoute('/workshops')

    expect(await screen.findByRole('alert')).toHaveTextContent('Mất kết nối.')
  })
})
