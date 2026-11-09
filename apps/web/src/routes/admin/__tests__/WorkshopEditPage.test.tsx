import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'

import type { ManagedWorkshopDetail } from '@/features/administration'
import { useToastStore } from '@/shared/store/toast-store'
import { signInAs } from '@/testing/factories/session'
import { aWorkshop, WORKSHOP_ID } from '@/testing/factories/workshop'
import { server } from '@/testing/msw-server'
import { problem } from '@/testing/problem'
import { renderRoute } from '@/testing/render-route'

const PATH = `/admin/workshops/${WORKSHOP_ID}`
const DETAIL = '/api/v1/workshops/:id'
const asManager = () => signInAs({ permissions: ['WORKSHOP_CREATE', 'WORKSHOP_EDIT', 'REPORT_VIEW'] })

beforeEach(() => useToastStore.setState({ toasts: [] }))
const toasts = () => useToastStore.getState().toasts.map((t) => t.message)

// A published workshop on 14/11/2026 13:30–16:30 Vietnam time, 42 of 60 seats taken, paid.
const published = (overrides: Partial<ManagedWorkshopDetail> = {}) =>
  aWorkshop({
    title: 'Nhập môn Machine Learning',
    status: 'PUBLISHED',
    startTime: '2026-11-14T06:30:00Z',
    endTime: '2026-11-14T09:30:00Z',
    capacity: 60,
    seatsTaken: 42,
    isPaid: true,
    price: 150000,
    ...overrides,
  })

/** Serves GET and PATCH of one workshop; the PATCH merges its body into what GET returns next, as the server would. */
function serveWorkshop(initial: ManagedWorkshopDetail, patch?: (body: Record<string, unknown>) => Response | undefined) {
  let current = initial
  const bodies: Record<string, unknown>[] = []
  server.use(
    http.get(DETAIL, () => HttpResponse.json(current)),
    http.patch(DETAIL, async ({ request }) => {
      const body = (await request.json()) as Record<string, unknown>
      bodies.push(body)
      const refusal = patch?.(body)
      if (refusal) return refusal
      current = { ...current, ...body, version: current.version + 1 } as ManagedWorkshopDetail
      return HttpResponse.json(current)
    }),
  )
  return { bodies, set: (next: ManagedWorkshopDetail) => (current = next) }
}

const field = (label: RegExp) => screen.getByLabelText(label)

describe('WorkshopEditPage: the form', () => {
  it('fills the form from the Master copy, shows the seats now, and saves nothing until something changes', async () => {
    serveWorkshop(published())
    asManager()

    renderRoute(PATH)

    expect(await screen.findByRole('heading', { level: 1, name: 'Nhập môn Machine Learning' })).toBeInTheDocument()
    expect(field(/^Tên workshop/)).toHaveValue('Nhập môn Machine Learning')
    expect(field(/^Bắt đầu/)).toHaveValue('2026-11-14T13:30')
    expect(field(/^Kết thúc/)).toHaveValue('2026-11-14T16:30')
    expect(field(/^Giá/)).toHaveValue('150000')
    expect(screen.getByText('Workshop có thu phí')).toBeInTheDocument()
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument() // the kind of fee is told, not asked
    const status = screen.getByRole('region', { name: 'Trạng thái' })
    expect(within(status).getByText('42')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Lưu thay đổi' })).toBeDisabled()
  })

  it('sends only the field that changed (D155) and says so', async () => {
    const server_ = serveWorkshop(published())
    asManager()
    const user = userEvent.setup()
    renderRoute(PATH)
    await screen.findByRole('heading', { level: 1 })

    await user.clear(field(/^Tên workshop/))
    await user.type(field(/^Tên workshop/), 'ML cho năm hai')
    await user.click(screen.getByRole('button', { name: 'Lưu thay đổi' }))

    await waitFor(() => expect(server_.bodies).toEqual([{ title: 'ML cho năm hai' }]))
    await waitFor(() => expect(toasts()).toContain('Đã lưu thay đổi.'))
    expect(screen.getByRole('button', { name: 'Lưu thay đổi' })).toBeDisabled() // the saved copy is the new "unchanged"
    expect(field(/^Tên workshop/)).toHaveValue('ML cho năm hai')
  })

  it('clears an optional text with null', async () => {
    const server_ = serveWorkshop(published({ description: 'Có mô tả' }))
    asManager()
    const user = userEvent.setup()
    renderRoute(PATH)
    await screen.findByRole('heading', { level: 1 })

    await user.clear(field(/^Mô tả/))
    await user.click(screen.getByRole('button', { name: 'Lưu thay đổi' }))

    await waitFor(() => expect(server_.bodies).toEqual([{ description: null }]))
  })

  it('asks before changing the time of a published workshop, shows the old and the new time, and tells the students', async () => {
    const server_ = serveWorkshop(published())
    asManager()
    const user = userEvent.setup()
    renderRoute(PATH)
    await screen.findByRole('heading', { level: 1 })

    fireEvent.change(field(/^Bắt đầu/), { target: { value: '2026-11-15T13:30' } })
    fireEvent.change(field(/^Kết thúc/), { target: { value: '2026-11-15T16:30' } })
    expect(screen.getByText(/Bạn đã đổi giờ\./)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Lưu thay đổi' }))

    const dialog = await screen.findByRole('alertdialog', { name: 'Đổi lịch workshop?' })
    expect(within(dialog).getByText('Thứ Bảy, 14/11/2026 · 13:30–16:30')).toBeInTheDocument()
    expect(within(dialog).getByText('Chủ Nhật, 15/11/2026 · 13:30–16:30')).toBeInTheDocument()
    expect(within(dialog).getByText(/sẽ nhận email và thông báo trong hộp thư/)).toBeInTheDocument()
    expect(server_.bodies).toHaveLength(0) // nothing is sent until the user confirms

    await user.click(within(dialog).getByRole('button', { name: 'Đổi lịch và gửi thông báo' }))

    await waitFor(() =>
      expect(server_.bodies).toEqual([{ startTime: '2026-11-15T06:30:00.000Z', endTime: '2026-11-15T09:30:00.000Z' }]),
    )
    await waitFor(() => expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument())
  })

  it('sends nothing when the user steps back from the reschedule dialog', async () => {
    const server_ = serveWorkshop(published())
    asManager()
    const user = userEvent.setup()
    renderRoute(PATH)
    await screen.findByRole('heading', { level: 1 })

    fireEvent.change(field(/^Bắt đầu/), { target: { value: '2026-11-15T13:30' } })
    fireEvent.change(field(/^Kết thúc/), { target: { value: '2026-11-15T16:30' } })
    await user.click(screen.getByRole('button', { name: 'Lưu thay đổi' }))
    await user.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Xem lại' }))

    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(server_.bodies).toHaveLength(0)
    expect(field(/^Bắt đầu/)).toHaveValue('2026-11-15T13:30') // what was typed is still there
  })

  it('saves a draft\'s new time straight away: no student can hold a seat yet', async () => {
    const server_ = serveWorkshop(published({ status: 'DRAFT', seatsTaken: 0 }))
    asManager()
    const user = userEvent.setup()
    renderRoute(PATH)
    await screen.findByRole('heading', { level: 1 })

    fireEvent.change(field(/^Bắt đầu/), { target: { value: '2026-11-13T13:30' } })
    expect(screen.queryByText(/Bạn đã đổi giờ\./)).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Lưu thay đổi' }))

    await waitFor(() => expect(server_.bodies).toHaveLength(1))
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
  })

  it('refuses a capacity below the seats taken at the field, before any request', async () => {
    const server_ = serveWorkshop(published())
    asManager()
    const user = userEvent.setup()
    renderRoute(PATH)
    await screen.findByRole('heading', { level: 1 })

    await user.clear(field(/^Sức chứa/))
    await user.type(field(/^Sức chứa/), '30')
    await user.click(screen.getByRole('button', { name: 'Lưu thay đổi' }))

    expect(await screen.findByText('Sức chứa không thể thấp hơn số chỗ đã giữ (42).')).toBeInTheDocument()
    expect(server_.bodies).toHaveLength(0)
  })

  it('on a 409 says what may have happened and "Tải lại" brings the server copy back into the form', async () => {
    const server_ = serveWorkshop(published(), () => problem(409))
    asManager()
    const user = userEvent.setup()
    renderRoute(PATH)
    await screen.findByRole('heading', { level: 1 })

    await user.clear(field(/^Tên workshop/))
    await user.type(field(/^Tên workshop/), 'Tên tôi gõ')
    await user.click(screen.getByRole('button', { name: 'Lưu thay đổi' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Workshop có thể vừa được thay đổi ở nơi khác')
    server_.set(published({ title: 'Tên do người khác đặt', version: 9 }))

    await user.click(within(alert).getByRole('button', { name: 'Tải lại' }))

    await waitFor(() => expect(field(/^Tên workshop/)).toHaveValue('Tên do người khác đặt'))
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('does not rewrite what is being typed when the workshop is read again in the background', async () => {
    serveWorkshop(published())
    asManager()
    const user = userEvent.setup()
    const { queryClient } = renderRoute(PATH)
    await screen.findByRole('heading', { level: 1 })

    await user.clear(field(/^Tên workshop/))
    await user.type(field(/^Tên workshop/), 'Đang gõ dở')
    await queryClient.invalidateQueries() // what a window-focus refetch does

    await waitFor(() => expect(queryClient.isFetching()).toBe(0))
    expect(field(/^Tên workshop/)).toHaveValue('Đang gõ dở')
  })

  it('shows the seat figure of a refetch without touching the form', async () => {
    const server_ = serveWorkshop(published())
    asManager()
    const { queryClient } = renderRoute(PATH)
    await screen.findByRole('heading', { level: 1 })

    server_.set(published({ seatsTaken: 50 }))
    await queryClient.invalidateQueries()

    await waitFor(() => expect(within(screen.getByRole('region', { name: 'Trạng thái' })).getByText('50')).toBeInTheDocument())
  })
})

describe('WorkshopEditPage: who may do what (D166)', () => {
  it('makes the fields read-only for WORKSHOP_CREATE alone, and offers no save, publish or cancel', async () => {
    serveWorkshop(published({ status: 'DRAFT', seatsTaken: 0 }))
    signInAs({ permissions: ['WORKSHOP_CREATE'] })

    renderRoute(PATH)

    expect(await screen.findByText(/Bạn có quyền tạo workshop nhưng không có quyền sửa/)).toBeInTheDocument()
    expect(field(/^Tên workshop/)).toBeDisabled()
    expect(screen.queryByRole('button', { name: 'Lưu thay đổi' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Công bố' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Hủy bản nháp' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Xem báo cáo' })).not.toBeInTheDocument()
  })

  it('makes a cancelled workshop read-only for everyone: it cannot be edited, published or cancelled again', async () => {
    serveWorkshop(published({ status: 'CANCELLED' }))
    asManager()

    renderRoute(PATH)

    expect(await screen.findByText('Workshop này đã bị hủy nên không sửa được.')).toBeInTheDocument()
    expect(field(/^Tên workshop/)).toBeDisabled()
    expect(screen.queryByRole('button', { name: 'Lưu thay đổi' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Hủy workshop' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Xem báo cáo' })).toHaveAttribute('href', `${PATH}/report`)
  })

  it('shows "Xem báo cáo" only with REPORT_VIEW', async () => {
    serveWorkshop(published())
    signInAs({ permissions: ['WORKSHOP_EDIT'] })
    renderRoute(PATH)
    await screen.findByRole('heading', { level: 1 })
    expect(screen.queryByRole('link', { name: 'Xem báo cáo' })).not.toBeInTheDocument()
  })

  it('says a workshop was not found', async () => {
    server.use(http.get(DETAIL, () => problem(404)))
    asManager()
    renderRoute(PATH)
    expect(await screen.findByText('Không tìm thấy workshop này.')).toBeInTheDocument()
  })
})

describe('WorkshopEditPage: publish and cancel', () => {
  it('publishes a draft after a confirmation, then shows it as published', async () => {
    let status: ManagedWorkshopDetail['status'] = 'DRAFT'
    let posted = 0
    server.use(
      http.get(DETAIL, () => HttpResponse.json(published({ status, seatsTaken: 0 }))),
      http.post(`${DETAIL}/publish`, () => {
        posted += 1
        status = 'PUBLISHED'
        return HttpResponse.json(published({ status, seatsTaken: 0 }))
      }),
    )
    asManager()
    const user = userEvent.setup()
    renderRoute(PATH)
    await user.click(await screen.findByRole('button', { name: 'Công bố' }))
    const dialog = await screen.findByRole('alertdialog', { name: 'Công bố workshop?' })
    expect(dialog).toHaveTextContent('không đưa nó về nháp được')
    expect(posted).toBe(0)

    await user.click(within(dialog).getByRole('button', { name: 'Công bố' }))

    await waitFor(() => expect(posted).toBe(1))
    await waitFor(() => expect(toasts()).toContain('Đã công bố workshop.'))
    await waitFor(() => expect(within(screen.getByRole('region', { name: 'Trạng thái' })).getByText('Đã công bố')).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: 'Công bố' })).not.toBeInTheDocument()
  })

  it('"Để sau" publishes nothing', async () => {
    let posted = 0
    server.use(
      http.get(DETAIL, () => HttpResponse.json(published({ status: 'DRAFT' }))),
      http.post(`${DETAIL}/publish`, () => ((posted += 1), HttpResponse.json(published()))),
    )
    asManager()
    const user = userEvent.setup()
    renderRoute(PATH)
    await user.click(await screen.findByRole('button', { name: 'Công bố' }))
    await user.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Để sau' }))
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(posted).toBe(0)
  })

  it('keeps the dialog open with the reason when publishing is refused (409)', async () => {
    server.use(
      http.get(DETAIL, () => HttpResponse.json(published({ status: 'DRAFT' }))),
      http.post(`${DETAIL}/publish`, () => problem(409)),
    )
    asManager()
    const user = userEvent.setup()
    renderRoute(PATH)
    await user.click(await screen.findByRole('button', { name: 'Công bố' }))
    await user.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Công bố' }))

    const dialog = await screen.findByRole('alertdialog')
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Workshop này không còn là bản nháp')
  })

  it('cancelling a published workshop says only what the backend does, and nothing about seats or money being returned', async () => {
    let cancelled = 0
    server.use(
      http.get(DETAIL, () => HttpResponse.json(published({ status: cancelled ? 'CANCELLED' : 'PUBLISHED' }))),
      http.post(`${DETAIL}/cancel`, () => {
        cancelled += 1
        return HttpResponse.json(published({ status: 'CANCELLED' }))
      }),
    )
    asManager()
    const user = userEvent.setup()
    renderRoute(PATH)
    await user.click(await screen.findByRole('button', { name: 'Hủy workshop' }))

    const dialog = await screen.findByRole('alertdialog', { name: 'Hủy workshop này?' })
    expect(dialog).toHaveTextContent('Sinh viên đang giữ chỗ hoặc đã đăng ký nhận email và thông báo trong hộp thư.')
    expect(dialog).toHaveTextContent('Đăng ký hiện có không bị hủy tự động.')
    expect(dialog).toHaveTextContent('chưa tự hoàn tiền')
    expect(dialog.textContent).not.toMatch(/giải phóng|trả lại chỗ được/i)

    await user.click(within(dialog).getByRole('button', { name: 'Hủy workshop' }))

    await waitFor(() => expect(cancelled).toBe(1))
    await waitFor(() => expect(toasts()).toContain('Đã hủy workshop.'))
    expect(await screen.findByText('Workshop này đã bị hủy nên không sửa được.')).toBeInTheDocument()
  })

  it('cancelling a draft says no one is told', async () => {
    server.use(http.get(DETAIL, () => HttpResponse.json(published({ status: 'DRAFT', seatsTaken: 0 }))))
    asManager()
    const user = userEvent.setup()
    renderRoute(PATH)
    await user.click(await screen.findByRole('button', { name: 'Hủy bản nháp' }))
    const dialog = await screen.findByRole('alertdialog', { name: 'Hủy bản nháp này?' })
    expect(dialog).toHaveTextContent('không có thông báo nào được gửi')
    expect(within(dialog).getByRole('button', { name: 'Giữ bản nháp' })).toBeInTheDocument()
  })

  it('says it when the workshop was already cancelled (409), and reads the workshop again', async () => {
    let gets = 0
    server.use(
      http.get(DETAIL, () => ((gets += 1), HttpResponse.json(published()))),
      http.post(`${DETAIL}/cancel`, () => problem(409)),
    )
    asManager()
    const user = userEvent.setup()
    renderRoute(PATH)
    await user.click(await screen.findByRole('button', { name: 'Hủy workshop' }))
    await user.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Hủy workshop' }))

    expect(await screen.findByText('Workshop này đã bị hủy hoặc đã kết thúc rồi.')).toBeInTheDocument()
    await waitFor(() => expect(gets).toBeGreaterThan(1))
  })
})
