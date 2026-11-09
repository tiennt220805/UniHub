import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'

import type { CreateWorkshopRequest } from '@/features/administration'
import { signInAs } from '@/testing/factories/session'
import { aWorkshop, WORKSHOP_ID } from '@/testing/factories/workshop'
import { server } from '@/testing/msw-server'
import { problem } from '@/testing/problem'
import { renderRoute } from '@/testing/render-route'

const asCreator = () => signInAs({ permissions: ['WORKSHOP_CREATE'] })

const type = async (user: ReturnType<typeof userEvent.setup>, label: string, text: string) => user.type(screen.getByLabelText(new RegExp(`^${label}`)), text)
const setTime = (label: string, value: string) => fireEvent.change(screen.getByLabelText(new RegExp(`^${label}`)), { target: { value } })

async function fillValid(user: ReturnType<typeof userEvent.setup>) {
  await type(user, 'Tên workshop', 'Cloud Computing với AWS')
  await type(user, 'Diễn giả', 'Nguyễn Thị Thu Hà')
  await type(user, 'Địa điểm', 'Phòng B1.203')
  setTime('Bắt đầu', '2026-11-18T13:30')
  setTime('Kết thúc', '2026-11-18T16:00')
  await type(user, 'Sức chứa', '40')
}

describe('WorkshopCreatePage', () => {
  it('sends the contract request (Vietnam times as instants), then opens the draft with the next steps', async () => {
    const bodies: CreateWorkshopRequest[] = []
    server.use(
      http.post('/api/v1/workshops', async ({ request }) => {
        bodies.push((await request.json()) as CreateWorkshopRequest)
        return HttpResponse.json(aWorkshop({ status: 'DRAFT', title: 'Cloud Computing với AWS' }), { status: 201 })
      }),
      http.get('/api/v1/workshops/:id', () => HttpResponse.json(aWorkshop({ status: 'DRAFT', title: 'Cloud Computing với AWS' }))),
    )
    asCreator()
    const user = userEvent.setup()
    const { router } = renderRoute('/admin/workshops/new')
    await screen.findByRole('heading', { name: 'Tạo workshop' })

    await fillValid(user)
    await user.click(screen.getByRole('button', { name: 'Tạo bản nháp' }))

    await waitFor(() => expect(router.state.location.pathname).toBe(`/admin/workshops/${WORKSHOP_ID}`))
    expect(bodies).toEqual([
      {
        title: 'Cloud Computing với AWS',
        description: null,
        speakerName: 'Nguyễn Thị Thu Hà',
        speakerBio: null,
        location: 'Phòng B1.203',
        startTime: '2026-11-18T06:30:00.000Z',
        endTime: '2026-11-18T09:00:00.000Z',
        capacity: 40,
        isPaid: false,
        price: null,
      },
    ])
    expect(await screen.findByRole('status')).toHaveTextContent('Đã tạo bản nháp.')
  })

  it('says what is wrong next to each field and sends nothing', async () => {
    let posted = 0
    server.use(http.post('/api/v1/workshops', () => ((posted += 1), HttpResponse.json({}, { status: 201 }))))
    asCreator()
    const user = userEvent.setup()
    renderRoute('/admin/workshops/new')
    await screen.findByRole('heading', { name: 'Tạo workshop' })

    await user.click(screen.getByRole('button', { name: 'Tạo bản nháp' }))

    expect(await screen.findByText('Nhập tên workshop.')).toBeInTheDocument()
    expect(screen.getByText('Nhập tên diễn giả.')).toBeInTheDocument()
    expect(screen.getByText('Nhập địa điểm.')).toBeInTheDocument()
    expect(screen.getByText('Chọn giờ bắt đầu.')).toBeInTheDocument()
    expect(screen.getByLabelText(/^Tên workshop/)).toHaveAttribute('aria-invalid', 'true')
    expect(posted).toBe(0)
  })

  it('refuses an end before the start at the end field', async () => {
    asCreator()
    const user = userEvent.setup()
    renderRoute('/admin/workshops/new')
    await screen.findByRole('heading', { name: 'Tạo workshop' })
    await fillValid(user)
    setTime('Kết thúc', '2026-11-18T12:00')

    await user.click(screen.getByRole('button', { name: 'Tạo bản nháp' }))

    expect(await screen.findByText('Giờ kết thúc phải sau giờ bắt đầu.')).toBeInTheDocument()
  })

  it('asks for a price only when the workshop is paid, and sends it as a number', async () => {
    const bodies: CreateWorkshopRequest[] = []
    server.use(
      http.post('/api/v1/workshops', async ({ request }) => {
        bodies.push((await request.json()) as CreateWorkshopRequest)
        return HttpResponse.json(aWorkshop({ status: 'DRAFT' }), { status: 201 })
      }),
      http.get('/api/v1/workshops/:id', () => HttpResponse.json(aWorkshop({ status: 'DRAFT' }))),
    )
    asCreator()
    const user = userEvent.setup()
    renderRoute('/admin/workshops/new')
    await screen.findByRole('heading', { name: 'Tạo workshop' })
    await fillValid(user)
    expect(screen.queryByLabelText(/^Giá/)).not.toBeInTheDocument()

    await user.click(screen.getByRole('checkbox', { name: 'Workshop có thu phí' }))
    await user.click(screen.getByRole('button', { name: 'Tạo bản nháp' }))
    expect(await screen.findByText('Nhập giá lớn hơn 0.')).toBeInTheDocument()

    await user.type(screen.getByLabelText(/^Giá/), '150.000')
    await user.click(screen.getByRole('button', { name: 'Tạo bản nháp' }))

    await waitFor(() => expect(bodies).toHaveLength(1))
    expect(bodies[0]).toMatchObject({ isPaid: true, price: 150000 })
  })

  it('shows the server refusing, keeps what was typed, and lets the user try again', async () => {
    let calls = 0
    server.use(
      http.post('/api/v1/workshops', () => {
        calls += 1
        return calls === 1 ? problem(400, { correlationId: 'c0ffee' }) : HttpResponse.json(aWorkshop({ status: 'DRAFT' }), { status: 201 })
      }),
      http.get('/api/v1/workshops/:id', () => HttpResponse.json(aWorkshop({ status: 'DRAFT' }))),
    )
    asCreator()
    const user = userEvent.setup()
    renderRoute('/admin/workshops/new')
    await screen.findByRole('heading', { name: 'Tạo workshop' })
    await fillValid(user)

    await user.click(screen.getByRole('button', { name: 'Tạo bản nháp' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Dữ liệu chưa hợp lệ.')
    expect(alert).toHaveTextContent('Mã tham chiếu: c0ffee')
    expect(screen.getByLabelText(/^Tên workshop/)).toHaveValue('Cloud Computing với AWS')

    await user.click(screen.getByRole('button', { name: 'Tạo bản nháp' }))
    await waitFor(() => expect(calls).toBe(2))
  })

  it('does not send a second create while the first is in flight', async () => {
    let calls = 0
    let release: () => void = () => {}
    server.use(
      http.post('/api/v1/workshops', async () => {
        calls += 1
        await new Promise<void>((resolve) => (release = resolve))
        return HttpResponse.json(aWorkshop({ status: 'DRAFT' }), { status: 201 })
      }),
      http.get('/api/v1/workshops/:id', () => HttpResponse.json(aWorkshop({ status: 'DRAFT' }))),
    )
    asCreator()
    const user = userEvent.setup()
    renderRoute('/admin/workshops/new')
    await screen.findByRole('heading', { name: 'Tạo workshop' })
    await fillValid(user)

    await user.click(screen.getByRole('button', { name: 'Tạo bản nháp' }))
    const pending = await screen.findByRole('button', { name: 'Đang tạo…' })
    expect(pending).toBeDisabled()
    await user.click(pending)
    release()

    await waitFor(() => expect(calls).toBe(1))
  })

  it('tells what comes after creating, and shows only WORKSHOP_CREATE sessions the page', async () => {
    asCreator()
    renderRoute('/admin/workshops/new')
    const aside = await screen.findByRole('complementary', { name: 'Sau khi tạo' })
    expect(within(aside).getByText('Tải ảnh bìa')).toBeInTheDocument()
    expect(within(aside).getByText('Tải brochure PDF')).toBeInTheDocument()
  })

  it('refuses a session without WORKSHOP_CREATE', async () => {
    signInAs({ permissions: ['WORKSHOP_EDIT'] })
    renderRoute('/admin/workshops/new')
    expect(await screen.findByRole('alert')).toHaveTextContent('Bạn không có quyền truy cập trang này.')
  })
})
