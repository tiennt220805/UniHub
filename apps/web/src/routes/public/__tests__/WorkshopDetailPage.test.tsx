import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { components } from '@/shared/api/schema.gen'
import { aRegistration, aReservedRegistration } from '@/testing/factories/registration'
import { signInAs } from '@/testing/factories/session'
import { aWorkshop, WORKSHOP_ID } from '@/testing/factories/workshop'
import { server } from '@/testing/msw-server'
import { problem } from '@/testing/problem'
import { renderRoute } from '@/testing/render-route'

type Workshop = components['schemas']['Workshop']

function serveDetail(body: Workshop) {
  server.use(
    http.get('/api/v1/workshops/:id', ({ params }) =>
      params.id === body.id ? HttpResponse.json(body) : new HttpResponse(null, { status: 404 }),
    ),
  )
}

function serveProblem(status: number) {
  server.use(
    http.get('/api/v1/workshops/:id', () =>
      HttpResponse.json(
        { type: 'about:blank', title: 'Problem', status },
        { status, headers: { 'Content-Type': 'application/problem+json' } },
      ),
    ),
  )
}

describe('WorkshopDetailPage', () => {
  it('renders speaker, location, schedule and description from GET /api/v1/workshops/{id}', async () => {
    serveDetail(aWorkshop())

    renderRoute(`/workshops/${WORKSHOP_ID}`)

    expect(await screen.findByRole('heading', { level: 1, name: 'Intro to Distributed Locks' })).toBeInTheDocument()
    expect(screen.getByText('Dr. Lan Nguyen')).toBeInTheDocument()
    expect(screen.getByText('Distributed systems lecturer.')).toBeInTheDocument()
    expect(screen.getByText('Hall A')).toBeInTheDocument()
    expect(screen.getByText('09:00 1 thg 11, 2026')).toBeInTheDocument()
    expect(screen.getByText('11:00 1 thg 11, 2026')).toBeInTheDocument()
    expect(screen.getByText('Redis locks and why they are only advisory.')).toBeInTheDocument()
  })

  it('shows remaining seats as capacity minus seatsTaken', async () => {
    serveDetail(aWorkshop({ capacity: 50, seatsTaken: 15 }))

    renderRoute(`/workshops/${WORKSHOP_ID}`)

    expect(await screen.findByText('Còn 35 / 50 chỗ')).toBeInTheDocument()
  })

  it('shows "Hết chỗ" when every seat is taken', async () => {
    serveDetail(aWorkshop({ capacity: 50, seatsTaken: 50 }))

    renderRoute(`/workshops/${WORKSHOP_ID}`)

    expect(await screen.findByText('Hết chỗ')).toBeInTheDocument()
  })

  it('shows the price for a paid workshop', async () => {
    serveDetail(aWorkshop({ isPaid: true, price: 150000 }))

    renderRoute(`/workshops/${WORKSHOP_ID}`)

    expect(await screen.findByText('150.000')).toBeInTheDocument()
    expect(screen.queryByText('Miễn phí')).not.toBeInTheDocument()
  })

  it('shows "Miễn phí" when isPaid is false', async () => {
    serveDetail(aWorkshop())

    renderRoute(`/workshops/${WORKSHOP_ID}`)

    expect(await screen.findByText('Miễn phí')).toBeInTheDocument()
  })

  it('renders the AI summary only once it is available', async () => {
    serveDetail(aWorkshop({ aiSummary: 'Locks are advisory; the DB check decides.' }))

    renderRoute(`/workshops/${WORKSHOP_ID}`)

    expect(await screen.findByText('Locks are advisory; the DB check decides.')).toBeInTheDocument()
  })

  it('omits optional sections when description, bio and AI summary are null', async () => {
    serveDetail(aWorkshop({ description: null, speakerBio: null, aiSummary: null }))

    renderRoute(`/workshops/${WORKSHOP_ID}`)

    await screen.findByRole('heading', { level: 1, name: 'Intro to Distributed Locks' })
    expect(screen.queryByRole('heading', { name: 'Giới thiệu' })).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Tóm tắt (AI)' })).not.toBeInTheDocument()
  })

  it('flags a cancelled workshop', async () => {
    serveDetail(aWorkshop({ status: 'CANCELLED' }))

    renderRoute(`/workshops/${WORKSHOP_ID}`)

    expect(await screen.findByRole('status')).toHaveTextContent('Workshop này đã bị hủy.')
  })

  it('shows "not found" when the API returns 404 (unknown id, or a DRAFT hidden from this caller)', async () => {
    serveProblem(404)

    renderRoute(`/workshops/${WORKSHOP_ID}`)

    expect(await screen.findByText('Không tìm thấy workshop.')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('shows "not found" for a malformed id the API rejects with 400', async () => {
    serveProblem(400)

    renderRoute('/workshops/not-a-uuid')

    expect(await screen.findByText('Không tìm thấy workshop.')).toBeInTheDocument()
  })

  it('shows an error message when the API fails', async () => {
    serveProblem(500)

    renderRoute(`/workshops/${WORKSHOP_ID}`)

    expect(await screen.findByRole('alert')).toHaveTextContent('Không tải được thông tin workshop.')
  })

  it('links back to the catalog', async () => {
    serveDetail(aWorkshop())

    renderRoute(`/workshops/${WORKSHOP_ID}`)

    expect(await screen.findByRole('link', { name: /Danh sách workshop/ })).toHaveAttribute('href', '/workshops')
  })
})

// ---------- registering (checklist 4: D8, D10, D15; web-coding-standards.md §6, §7, §10) ----------

type Attempt = { key: string | null; body: unknown }

/** Records every POST /registrations; `respond` gets the 1-based attempt number. */
function serveRegistration(respond: (attempt: number) => Response) {
  const attempts: Attempt[] = []
  server.use(
    http.post('/api/v1/registrations', async ({ request }) => {
      attempts.push({ key: request.headers.get('Idempotency-Key'), body: await request.json() })
      return respond(attempts.length)
    }),
  )
  return attempts
}

/** Serves the workshop and counts the reads, so a refresh after a write is observable. */
function serveCountedDetail(body: Workshop = aWorkshop()) {
  const reads = { count: 0 }
  server.use(
    http.get('/api/v1/workshops/:id', () => {
      reads.count += 1
      return HttpResponse.json(body)
    }),
  )
  return reads
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

async function openDetail() {
  const result = renderRoute(`/workshops/${WORKSHOP_ID}`)
  await screen.findByRole('heading', { level: 1, name: aWorkshop().title })
  return result
}

describe('WorkshopDetailPage: the register button', () => {
  it('invites an anonymous visitor to sign in, and offers no button', async () => {
    serveCountedDetail()

    await openDetail()

    expect(screen.getByRole('link', { name: 'Đăng nhập để đăng ký' })).toHaveAttribute('href', '/login')
    expect(screen.queryByRole('button', { name: 'Đăng ký tham dự' })).not.toBeInTheDocument()
  })

  it('tells a signed-in user without REGISTRATION_CREATE that they cannot register', async () => {
    serveCountedDetail()
    signInAs({ permissions: [] })

    await openDetail()

    expect(screen.getByText(/không có quyền đăng ký/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Đăng ký tham dự' })).not.toBeInTheDocument()
  })

  it('registers for a free workshop: one idempotency key, a CONFIRMED answer, a seat count refresh', async () => {
    const reads = serveCountedDetail()
    const attempts = serveRegistration(() => HttpResponse.json(aRegistration(), { status: 201 }))
    signInAs()
    const user = userEvent.setup()
    await openDetail()

    await user.click(screen.getByRole('button', { name: 'Đăng ký tham dự' }))

    expect(await screen.findByRole('status')).toHaveTextContent('Đăng ký thành công')
    expect(attempts).toHaveLength(1)
    expect(attempts[0].key).toMatch(UUID)
    expect(attempts[0].body).toEqual({ workshopId: WORKSHOP_ID })
    await waitFor(() => expect(reads.count).toBe(2)) // the seat count is read again
  })

  it('sends a paid registration on to checkout', async () => {
    serveCountedDetail(aWorkshop({ isPaid: true, price: 150000 }))
    serveRegistration(() => HttpResponse.json(aReservedRegistration(), { status: 201 }))
    signInAs()
    const user = userEvent.setup()
    const { router } = await openDetail()

    await user.click(screen.getByRole('button', { name: 'Đăng ký tham dự' }))

    await waitFor(() =>
      expect(router.state.location.pathname).toBe(`/me/checkout/${aReservedRegistration().id}`),
    )
  })

  it.each([
    ['sold-out', 'Workshop đã hết chỗ.'],
    ['already-registered', 'Bạn đã đăng ký workshop này rồi.'],
    ['registration-not-open', 'Workshop này hiện không mở đăng ký.'],
  ])('shows the reason of a 409 %s', async (slug, message) => {
    serveCountedDetail()
    serveRegistration(() => problem(409, { slug }))
    signInAs()
    const user = userEvent.setup()
    await openDetail()

    await user.click(screen.getByRole('button', { name: 'Đăng ký tham dự' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(message)
  })

  it('falls back to the status for a 409 whose type it does not know (AC8.3)', async () => {
    serveCountedDetail()
    serveRegistration(() => problem(409, { slug: 'a-reason-from-a-newer-server' }))
    signInAs()
    const user = userEvent.setup()
    await openDetail()

    await user.click(screen.getByRole('button', { name: 'Đăng ký tham dự' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Không thể đăng ký workshop ở trạng thái hiện tại.',
    )
  })

  it('shows the correlation id so the user can quote it to support', async () => {
    serveCountedDetail()
    serveRegistration(() => problem(500, { correlationId: 'corr-123' }))
    signInAs()
    const user = userEvent.setup()
    await openDetail()

    await user.click(screen.getByRole('button', { name: 'Đăng ký tham dự' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Mã tham chiếu: corr-123')
  })

  it('a definitive answer ends the intent: the next submit gets a new key (§10)', async () => {
    serveCountedDetail()
    const attempts = serveRegistration(() => problem(409, { slug: 'sold-out' }))
    signInAs()
    const user = userEvent.setup()
    await openDetail()

    await user.click(screen.getByRole('button', { name: 'Đăng ký tham dự' }))
    await screen.findByRole('alert')
    await user.click(screen.getByRole('button', { name: 'Đăng ký tham dự' }))
    await waitFor(() => expect(attempts).toHaveLength(2))

    expect(attempts[1].key).not.toBe(attempts[0].key)
  })

  it('a key still being processed keeps the intent: the next submit reuses the key', async () => {
    serveCountedDetail()
    const attempts = serveRegistration((n) =>
      n === 1 ? problem(409, { slug: 'idempotency-key-in-use' }) : HttpResponse.json(aRegistration(), { status: 201 }),
    )
    signInAs()
    const user = userEvent.setup()
    await openDetail()

    await user.click(screen.getByRole('button', { name: 'Đăng ký tham dự' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('đang được xử lý')
    await user.click(screen.getByRole('button', { name: 'Đăng ký tham dự' }))

    expect(await screen.findByRole('status')).toHaveTextContent('Đăng ký thành công')
    expect(attempts).toHaveLength(2)
    expect(attempts[1].key).toBe(attempts[0].key)
  })

  it('a reused key (422) is a client bug: the intent ends and the next submit gets a new key', async () => {
    serveCountedDetail()
    const attempts = serveRegistration(() => problem(422, { slug: 'idempotency-key-reused' }))
    signInAs()
    const user = userEvent.setup()
    await openDetail()

    await user.click(screen.getByRole('button', { name: 'Đăng ký tham dự' }))
    await screen.findByRole('alert')
    await user.click(screen.getByRole('button', { name: 'Đăng ký tham dự' }))
    await waitFor(() => expect(attempts).toHaveLength(2))

    expect(attempts[1].key).not.toBe(attempts[0].key)
  })

  it('does not retry a 429 by itself, and says how long to wait', async () => {
    serveCountedDetail()
    const attempts = serveRegistration(() => problem(429, { retryAfter: 20 }))
    signInAs()
    const user = userEvent.setup()
    await openDetail()

    await user.click(screen.getByRole('button', { name: 'Đăng ký tham dự' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('sau 20 giây')
    expect(attempts).toHaveLength(1)
  })

  it('a double click is one intent, one request', async () => {
    serveCountedDetail()
    let release: () => void = () => {}
    const gate = new Promise<void>((resolve) => (release = resolve))
    const attempts: Attempt[] = []
    server.use(
      http.post('/api/v1/registrations', async ({ request }) => {
        attempts.push({ key: request.headers.get('Idempotency-Key'), body: null })
        await gate
        return HttpResponse.json(aRegistration(), { status: 201 })
      }),
    )
    signInAs()
    const user = userEvent.setup()
    await openDetail()

    const button = screen.getByRole('button', { name: 'Đăng ký tham dự' })
    await user.dblClick(button)
    release()

    expect(await screen.findByRole('status')).toHaveTextContent('Đăng ký thành công')
    expect(attempts).toHaveLength(1)
  })
})

describe('WorkshopDetailPage: the link to the report', () => {
  it('offers the report to a session holding REPORT_VIEW, pointing at its page', async () => {
    serveDetail(aWorkshop())
    signInAs({ permissions: ['REPORT_VIEW'] })

    renderRoute(`/workshops/${WORKSHOP_ID}`)

    const link = await screen.findByRole('link', { name: 'Xem báo cáo' })
    expect(link).toHaveAttribute('href', `/admin/workshops/${WORKSHOP_ID}/report`)
  })

  it('offers nothing to an anonymous visitor', async () => {
    serveDetail(aWorkshop())

    renderRoute(`/workshops/${WORKSHOP_ID}`)
    await screen.findByRole('heading', { level: 1, name: 'Intro to Distributed Locks' })
    expect(screen.queryByRole('link', { name: 'Xem báo cáo' })).not.toBeInTheDocument()
  })

  it('offers nothing to a session without REPORT_VIEW, whatever else it holds', async () => {
    serveDetail(aWorkshop())
    signInAs({ permissions: ['REGISTRATION_CREATE', 'WORKSHOP_EDIT'] })

    renderRoute(`/workshops/${WORKSHOP_ID}`)

    await screen.findByRole('heading', { level: 1, name: 'Intro to Distributed Locks' })
    expect(screen.queryByRole('link', { name: 'Xem báo cáo' })).not.toBeInTheDocument()
  })
})

describe('WorkshopDetailPage: a busy server (503) and a lost connection', () => {
  beforeEach(() => vi.useFakeTimers({ shouldAdvanceTime: true }))
  afterEach(() => vi.useRealTimers())

  const click = () =>
    userEvent.setup({ advanceTimers: vi.advanceTimersByTime }).click(
      screen.getByRole('button', { name: 'Đăng ký tham dự' }),
    )

  it('retries a 503 by itself, at most three times, always with the same key (AC15.3)', async () => {
    serveCountedDetail()
    const attempts = serveRegistration(() => problem(503, { retryAfter: 1 }))
    signInAs()
    await openDetail()

    await click()
    await vi.advanceTimersByTimeAsync(20_000)

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Hệ thống đang bận'))
    expect(attempts).toHaveLength(4) // the first try and three retries
    expect(new Set(attempts.map((a) => a.key)).size).toBe(1)
  })

  it('waits for the Retry-After the server sent before trying again', async () => {
    serveCountedDetail()
    const attempts = serveRegistration(() => problem(503, { retryAfter: 30 }))
    signInAs()
    await openDetail()

    await click()
    await vi.advanceTimersByTimeAsync(10_000)

    expect(attempts).toHaveLength(1) // 10 s in, the 30 s the server asked for has not passed
    expect(screen.getByRole('status')).toHaveTextContent('đang thử lại')
    await vi.advanceTimersByTimeAsync(25_000)
    await waitFor(() => expect(attempts).toHaveLength(2))
  })

  it('succeeds when the server recovers during the retries', async () => {
    serveCountedDetail()
    const attempts = serveRegistration((n) =>
      n < 3 ? problem(503, { retryAfter: 1 }) : HttpResponse.json(aRegistration(), { status: 201 }),
    )
    signInAs()
    await openDetail()

    await click()
    await vi.advanceTimersByTimeAsync(10_000)

    expect(await screen.findByText(/Đăng ký thành công/)).toBeInTheDocument()
    expect(attempts).toHaveLength(3)
    expect(new Set(attempts.map((a) => a.key)).size).toBe(1)
  })

  it('retries a lost connection with the same key', async () => {
    serveCountedDetail()
    const attempts: Attempt[] = []
    server.use(
      http.post('/api/v1/registrations', ({ request }) => {
        attempts.push({ key: request.headers.get('Idempotency-Key'), body: null })
        return attempts.length === 1 ? HttpResponse.error() : HttpResponse.json(aRegistration(), { status: 201 })
      }),
    )
    signInAs()
    await openDetail()

    await click()
    await vi.advanceTimersByTimeAsync(5_000)

    expect(await screen.findByText(/Đăng ký thành công/)).toBeInTheDocument()
    expect(attempts).toHaveLength(2)
    expect(attempts[1].key).toBe(attempts[0].key)
  })

  it('after the retries run out, the next manual submit still carries the same key', async () => {
    serveCountedDetail()
    const attempts = serveRegistration(() => problem(503, { retryAfter: 1 }))
    signInAs()
    await openDetail()

    await click()
    await vi.advanceTimersByTimeAsync(20_000)
    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument())
    await click()
    await waitFor(() => expect(attempts.length).toBeGreaterThan(4))

    expect(new Set(attempts.map((a) => a.key)).size).toBe(1)
  })
})

describe('WorkshopDetailPage: the seat count follows the other students (AC15.1)', () => {
  beforeEach(() => vi.useFakeTimers({ shouldAdvanceTime: true }))
  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  const setVisibility = (state: 'visible' | 'hidden') => {
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue(state)
    document.dispatchEvent(new Event('visibilitychange'))
  }

  it('reads the workshop again every 5 seconds while the page is open', async () => {
    const reads = serveCountedDetail()
    await openDetail()
    expect(reads.count).toBe(1)

    await vi.advanceTimersByTimeAsync(5_100)
    await waitFor(() => expect(reads.count).toBe(2))
    await vi.advanceTimersByTimeAsync(5_000)
    await waitFor(() => expect(reads.count).toBe(3))
  })

  it('stops reading while the tab is hidden', async () => {
    const reads = serveCountedDetail()
    await openDetail()
    setVisibility('hidden')
    const before = reads.count

    await vi.advanceTimersByTimeAsync(30_000)

    expect(reads.count).toBe(before)
  })

  it('stops reading once the user leaves the detail page', async () => {
    const reads = serveCountedDetail()
    server.use(http.get('/api/v1/workshops', () => HttpResponse.json({ items: [], page: { page: 0, size: 20, totalElements: 0, totalPages: 0 } })))
    const { router } = await openDetail()

    await router.navigate('/workshops')
    await screen.findByRole('heading', { name: /workshop/i })
    const before = reads.count
    await vi.advanceTimersByTimeAsync(30_000)

    expect(reads.count).toBe(before)
  })
})
