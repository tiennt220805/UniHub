import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'

import { TicketQrCode } from '@/features/registrations'
import { aRegistration, aReservedRegistration, REGISTRATION_ID } from '@/testing/factories/registration'
import { signInAs } from '@/testing/factories/session'
import { aWorkshop } from '@/testing/factories/workshop'
import { server } from '@/testing/msw-server'
import { problem } from '@/testing/problem'
import { renderRoute } from '@/testing/render-route'
import { formatDateTime } from '@/shared/lib/format'

const PATH = `/me/registrations/${REGISTRATION_ID}`

// The size of a real ticket: three base64url parts of 20, 152 and 86 characters, 260 in all
// (checkin.md §5, pinned by NimbusEs256TicketSignerTest).
const QR_PAYLOAD = `eyJhbGciOiJFUzI1NiJ9.${'p'.repeat(152)}.${'s'.repeat(86)}`
const TICKET_CODE = '0123456789ABCDEFGHJKMNPQRS'

const serveRegistration = (registration: ReturnType<typeof aRegistration>) =>
  server.use(http.get('/api/v1/registrations/:id', () => HttpResponse.json(registration)))

const serveWorkshop = () =>
  server.use(http.get('/api/v1/workshops/:id', () => HttpResponse.json(aWorkshop({ title: 'Kỹ năng thuyết trình' }))))

const issued = (overrides = {}) =>
  aRegistration({ status: 'CONFIRMED', ticketCode: TICKET_CODE, qrPayload: QR_PAYLOAD, ...overrides })

/** The dark modules of a QR drawn by the library: what the symbol encodes, as a path. */
const modulesOfMarkup = (markup: string) => {
  const holder = document.createElement('div')
  holder.innerHTML = markup
  return modulesOf(holder.querySelector('svg'))
}
const modulesOf = (svg: Element | null) => {
  const paths = svg?.querySelectorAll('path') ?? []
  return paths[paths.length - 1]?.getAttribute('d') ?? null
}

describe('RegistrationTicketPage', () => {
  it('draws a QR that encodes exactly the stored qrPayload, and shows the code, workshop and time', async () => {
    serveRegistration(issued())
    serveWorkshop()
    signInAs()

    renderRoute(PATH)

    const qr = await screen.findByRole('img', { name: 'Mã QR e-ticket' })
    // 260 characters at level Q is version 15, 77 modules, plus a quiet zone of 4 on each side.
    expect(qr).toHaveAttribute('viewBox', '0 0 85 85')
    const drawn = modulesOf(qr)
    expect(drawn).toBeTruthy()
    // The same symbol, drawn by the same library for the payload: equal for the payload, different
    // for anything else, in particular the ticket code, which is not what a Check-in Client verifies.
    expect(drawn).toBe(modulesOfMarkup(renderToStaticMarkup(<TicketQrCode value={QR_PAYLOAD} />)))
    expect(drawn).not.toBe(modulesOfMarkup(renderToStaticMarkup(<TicketQrCode value={TICKET_CODE} />)))
    expect(screen.getByText(TICKET_CODE)).toBeInTheDocument()
    expect(await screen.findByRole('link', { name: 'Kỹ năng thuyết trình' })).toBeInTheDocument()
    expect(screen.getByText(formatDateTime('2026-11-01T02:00:00Z'))).toBeInTheDocument()
    expect(screen.queryByText(QR_PAYLOAD)).not.toBeInTheDocument()
  })

  it('draws the QR as plain inline SVG: no image, no style attribute, nothing the CSP would block (AC56.3)', async () => {
    serveRegistration(issued())
    serveWorkshop()
    signInAs()

    renderRoute(PATH)

    const qr = await screen.findByRole('img', { name: 'Mã QR e-ticket' })
    expect(qr.tagName.toLowerCase()).toBe('svg')
    expect(qr.querySelector('image, foreignObject')).toBeNull()
    expect(document.querySelector('main [style], main img')).toBeNull()
  })

  it('says the ticket was not issued for a confirmed registration that has none, and draws no QR', async () => {
    serveRegistration(aRegistration({ status: 'CONFIRMED', ticketCode: null, qrPayload: null }))
    serveWorkshop()
    signInAs()

    renderRoute(PATH)

    expect(await screen.findByText('Vé chưa được phát cho đăng ký này.')).toBeInTheDocument()
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
  })

  it('shows no ticket while RESERVED, and a paid one keeps the way to checkout', async () => {
    serveRegistration(aReservedRegistration())
    serveWorkshop()
    signInAs()

    renderRoute(PATH)

    expect(await screen.findByText(/Chưa có vé/)).toBeInTheDocument()
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Thanh toán để nhận vé' })).toHaveAttribute(
      'href',
      `/me/checkout/${REGISTRATION_ID}`,
    )
  })

  it.each([
    ['CANCELLED', 'Đăng ký này đã bị hủy, vé không còn hiệu lực.'],
    ['EXPIRED', 'Đã hết thời gian giữ chỗ, đăng ký này không còn hiệu lực.'],
  ] as const)(
    '%s shows its status and no QR, even though the row still carries the signed payload',
    async (status, message) => {
      serveRegistration(issued({ status }))
      serveWorkshop()
      signInAs()

      renderRoute(PATH)

      expect(await screen.findByText(message)).toBeInTheDocument()
      expect(screen.queryByRole('img')).not.toBeInTheDocument()
      expect(screen.queryByText(TICKET_CODE)).not.toBeInTheDocument()
      expect(screen.queryByRole('link', { name: /Thanh toán/ })).not.toBeInTheDocument()
    },
  )

  it("refuses another student's registration and shows nothing of a ticket (S-18)", async () => {
    server.use(http.get('/api/v1/registrations/:id', () => problem(403)))
    signInAs()

    renderRoute(PATH)

    expect(await screen.findByRole('alert')).toHaveTextContent('Bạn không phải là người đăng ký này.')
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
    expect(screen.queryByText(/Mã vé/)).not.toBeInTheDocument()
  })

  it.each([
    [404, 'Không tìm thấy đăng ký.'],
    [400, 'Không tìm thấy đăng ký.'],
    [429, 'Bạn đang gửi quá nhiều yêu cầu.'],
    [500, 'Không tải được thông tin đăng ký.'],
  ])('answers %i with a message and, when the server sent one, its correlation id', async (status, message) => {
    server.use(http.get('/api/v1/registrations/:id', () => problem(status, { correlationId: 'corr-7' })))
    signInAs()

    renderRoute(PATH)

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(message)
    expect(alert).toHaveTextContent('Mã tham chiếu: corr-7')
  })

  it('keeps the QR on white and at a size a phone screen can be read from, at level Q (D152)', async () => {
    serveRegistration(issued())
    serveWorkshop()
    signInAs()

    renderRoute(PATH)

    const qr = await screen.findByRole('img', { name: 'Mã QR e-ticket' })
    // The panel is white whatever the theme, and the symbol carries its own white quiet zone.
    expect(qr.parentElement).toHaveClass('bg-white')
    expect(qr.querySelector('path')).toHaveAttribute('fill', '#FFFFFF')
    // 288 px at most on a phone, 256 from md up: never below the 256 a camera can read from another screen.
    expect(qr).toHaveAttribute('width', '288')
    expect(qr).toHaveClass('max-w-72', 'md:max-w-64')
  })

  it('shows the status in words and the ticket type, with the instruction and the brightness hint', async () => {
    serveRegistration(issued({ ticketType: 'PAID' }))
    serveWorkshop()
    signInAs()

    renderRoute(PATH)

    const ticket = await screen.findByRole('region', { name: 'E-ticket' })
    expect(within(ticket).getByText('Đã xác nhận')).toBeInTheDocument()
    expect(within(ticket).getByText(/Có phí · Đăng ký lúc/)).toBeInTheDocument()
    expect(within(ticket).getByText('Xuất trình mã QR này cho nhân viên check-in tại cửa workshop.')).toBeInTheDocument()
    const hint = within(ticket).getByText('Tăng độ sáng màn hình khi đưa vé cho nhân viên.')
    expect(hint).toHaveClass('md:hidden')
  })

  it('offers a retry when the registration cannot be loaded', async () => {
    let calls = 0
    server.use(
      http.get('/api/v1/registrations/:id', () => {
        calls += 1
        return calls === 1 ? problem(500) : HttpResponse.json(issued())
      }),
    )
    serveWorkshop()
    signInAs()
    const user = userEvent.setup()

    renderRoute(PATH)

    await user.click(await screen.findByRole('button', { name: 'Thử lại' }))
    expect(await screen.findByRole('img', { name: 'Mã QR e-ticket' })).toBeInTheDocument()
  })

  it('shows a loading state before the registration arrives', async () => {
    serveRegistration(issued())
    serveWorkshop()
    signInAs()

    renderRoute(PATH)

    expect(screen.getByText('Đang tải…')).toBeInTheDocument()
    expect(await screen.findByRole('img', { name: 'Mã QR e-ticket' })).toBeInTheDocument()
  })

  it('still shows the ticket when the workshop cannot be read', async () => {
    serveRegistration(issued())
    server.use(http.get('/api/v1/workshops/:id', () => problem(500)))
    signInAs()

    renderRoute(PATH)

    const qr = await screen.findByRole('img', { name: 'Mã QR e-ticket' })
    expect(qr).toBeInTheDocument()
    expect(await within(screen.getByRole('main')).findByRole('link', { name: 'Workshop' })).toHaveAttribute(
      'href',
      `/workshops/${aWorkshop().id}`,
    )
  })

  it('sends someone who is not signed in to sign-in and remembers the ticket page to return to', () => {
    const { router } = renderRoute(PATH)

    expect(router.state.location.pathname).toBe('/login')
    expect(router.state.location.state).toMatchObject({ from: { pathname: PATH } })
  })

  it('links back to the list', async () => {
    serveRegistration(issued())
    serveWorkshop()
    signInAs()

    renderRoute(PATH)

    const back = await screen.findByRole('link', { name: '← Đăng ký của tôi' })
    expect(back).toHaveAttribute('href', '/me/registrations')
    expect(within(document.body).getByRole('heading', { name: 'E-ticket' })).toBeInTheDocument()
  })
})
