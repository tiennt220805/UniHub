import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { Permission } from '@/features/auth'
import type { Payment } from '@/features/payments'
import { env } from '@/shared/config/env'
import { aPayment, PAYMENT_ID } from '@/testing/factories/payment'
import { aRegistration, aReservedRegistration, REGISTRATION_ID } from '@/testing/factories/registration'
import { signInAs } from '@/testing/factories/session'
import { aWorkshop } from '@/testing/factories/workshop'
import { server } from '@/testing/msw-server'
import { problem } from '@/testing/problem'
import { renderRoute } from '@/testing/render-route'

const PATH = `/me/checkout/${REGISTRATION_ID}`
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

type Charge = { key: string | null; body: { registrationId: string; paymentMethodToken: string } }

/** A server whose state a test moves forward, so polling has something to observe. */
function serveCheckout(initial: {
  registration?: ReturnType<typeof aRegistration>
  payment?: ReturnType<typeof aPayment>
} = {}) {
  const state = {
    registration: initial.registration ?? aReservedRegistration(),
    payment: initial.payment ?? aPayment(),
    registrationReads: 0,
    paymentReads: 0,
    charges: [] as Charge[],
    // A Payment answers 201 and becomes what GET /payments/{id} says from then on; a Response is
    // sent as it is (a problem, a dropped connection).
    onCharge: ((): Payment | Response => aPayment({ status: 'SUCCEEDED' })) as (
      attempt: number,
    ) => Payment | Response,
  }
  server.use(
    http.get('/api/v1/workshops/:id', () => HttpResponse.json(aWorkshop({ isPaid: true, price: 150000 }))),
    http.get('/api/v1/registrations/:id', () => {
      state.registrationReads += 1
      return HttpResponse.json(state.registration)
    }),
    http.get('/api/v1/payments/:id', () => {
      state.paymentReads += 1
      return HttpResponse.json(state.payment)
    }),
    http.post('/api/v1/payments/charge', async ({ request }) => {
      state.charges.push({
        key: request.headers.get('Idempotency-Key'),
        body: (await request.json()) as Charge['body'],
      })
      const outcome = state.onCharge(state.charges.length)
      if (outcome instanceof Response) return outcome
      state.payment = outcome
      return HttpResponse.json(outcome, { status: 201 })
    }),
  )
  return state
}

beforeEach(() => {
  env.paymentTestMode = true
  signInAs()
})
afterEach(() => {
  env.paymentTestMode = false
})

const payButton = () => screen.findByRole('button', { name: 'Thanh toán' })

describe('CheckoutPage: the test-token dropdown (D11)', () => {
  it('offers a successful and a declined Stripe test token in the sandbox (AC11.1)', async () => {
    serveCheckout()
    renderRoute(PATH)

    const select = await screen.findByLabelText(/Phương thức thanh toán/)

    const options = within(select).getAllByRole('option')
    expect(options.map((o) => (o as HTMLOptionElement).value)).toEqual(['pm_card_visa', 'pm_card_chargeDeclined'])
  })

  it('has no dropdown and no way to pay outside test-key mode (AC11.1)', async () => {
    env.paymentTestMode = false
    serveCheckout()
    renderRoute(PATH)

    expect(await screen.findByText(/chưa khả dụng trong phiên bản này/)).toBeInTheDocument()
    expect(screen.queryByLabelText(/Phương thức thanh toán/)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Thanh toán' })).not.toBeInTheDocument()
  })
})

describe('CheckoutPage: paying', () => {
  it('charges with the chosen token under one idempotency key and follows the payment in the URL', async () => {
    const server_ = serveCheckout()
    const user = userEvent.setup()
    const { router } = renderRoute(PATH)

    await user.click(await payButton())

    expect(await screen.findByText('Thanh toán thành công.')).toBeInTheDocument()
    expect(server_.charges).toHaveLength(1)
    expect(server_.charges[0].key).toMatch(UUID)
    expect(server_.charges[0].body).toEqual({
      registrationId: REGISTRATION_ID,
      paymentMethodToken: 'pm_card_visa',
    })
    await waitFor(() => expect(router.state.location.search).toBe(`?payment=${PAYMENT_ID}`))
  })

  it('shows the real status of a payment that succeeded while the registration is still being confirmed', async () => {
    const state = serveCheckout()
    state.onCharge = () => aPayment({ status: 'SUCCEEDED' })
    const user = userEvent.setup()
    renderRoute(PATH)

    await user.click(await payButton())

    expect(await screen.findByText('Đang xác nhận đăng ký của bạn…')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Thanh toán' })).not.toBeInTheDocument()
  })

  it('a declined token comes back FAILED, and trying again is a new attempt with a new key (AC11.2)', async () => {
    const state = serveCheckout()
    state.onCharge = (n) =>
      n === 1 ? aPayment({ status: 'FAILED' }) : aPayment({ id: 'second-payment', status: 'SUCCEEDED' })
    const user = userEvent.setup()
    renderRoute(PATH)

    await user.selectOptions(await screen.findByLabelText(/Phương thức thanh toán/), 'pm_card_chargeDeclined')
    await user.click(await payButton())
    expect(await screen.findByText(/Thanh toán bị từ chối/)).toBeInTheDocument()

    // The form is offered again; the same card or another, the key is new.
    await user.click(await payButton())

    await waitFor(() => expect(state.charges).toHaveLength(2))
    expect(state.charges[1].key).not.toBe(state.charges[0].key)
    expect(state.charges[0].body.paymentMethodToken).toBe('pm_card_chargeDeclined')
  })
})

describe('CheckoutPage: waiting for the result (D15)', () => {
  beforeEach(() => vi.useFakeTimers({ shouldAdvanceTime: true }))
  afterEach(() => vi.useRealTimers())

  const userWithTimers = () => userEvent.setup({ advanceTimers: vi.advanceTimersByTime })

  it('polls every 2 seconds until the registration is confirmed, then stops (AC15.2)', async () => {
    const state = serveCheckout()
    state.onCharge = () => aPayment({ status: 'SUCCEEDED' })
    const user = userWithTimers()
    renderRoute(PATH)
    await user.click(await payButton())
    await screen.findByText('Đang xác nhận đăng ký của bạn…')

    state.registration = aRegistration({ ticketType: 'PAID', status: 'CONFIRMED' })
    await vi.advanceTimersByTimeAsync(2_100)

    expect(await screen.findByText(/Đăng ký của bạn đã được xác nhận/)).toBeInTheDocument()
    const reads = state.registrationReads
    await vi.advanceTimersByTimeAsync(20_000)
    expect(state.registrationReads).toBe(reads)
  })

  it('follows a payment that is still PENDING until it settles', async () => {
    const state = serveCheckout()
    state.onCharge = () => aPayment({ status: 'PENDING' })
    const user = userWithTimers()
    renderRoute(PATH)
    await user.click(await payButton())
    expect(await screen.findByText('Đang xử lý thanh toán…')).toBeInTheDocument()

    state.payment = aPayment({ status: 'SUCCEEDED' })
    await vi.advanceTimersByTimeAsync(2_100)

    expect(await screen.findByText('Thanh toán thành công.')).toBeInTheDocument()
  })

  it('stops polling a failed payment at once, and offers the form again', async () => {
    const state = serveCheckout()
    state.onCharge = () => aPayment({ status: 'PENDING' })
    const user = userWithTimers()
    renderRoute(PATH)
    await user.click(await payButton())
    await screen.findByText('Đang xử lý thanh toán…')

    state.payment = aPayment({ status: 'FAILED' })
    await vi.advanceTimersByTimeAsync(2_100)
    await screen.findByText(/Thanh toán bị từ chối/)
    const reads = state.paymentReads
    await vi.advanceTimersByTimeAsync(20_000)

    expect(state.paymentReads).toBe(reads)
    expect(screen.getByRole('button', { name: 'Thanh toán' })).toBeInTheDocument()
  })

  it('gives up after 2 minutes: "still processing" and a manual refresh (AC15.2)', async () => {
    const state = serveCheckout()
    state.onCharge = () => aPayment({ status: 'PENDING' })
    const user = userWithTimers()
    renderRoute(PATH)
    await user.click(await payButton())
    await screen.findByText('Đang xử lý thanh toán…')

    await vi.advanceTimersByTimeAsync(121_000)

    expect(await screen.findByText(/Vẫn đang xử lý/)).toBeInTheDocument()
    const reads = state.registrationReads
    await vi.advanceTimersByTimeAsync(30_000)
    expect(state.registrationReads).toBe(reads) // no more polling

    await user.click(screen.getByRole('button', { name: 'Làm mới' }))
    await vi.advanceTimersByTimeAsync(2_100)
    await waitFor(() => expect(state.registrationReads).toBeGreaterThan(reads)) // a fresh window
  })

  it('a new payment after an expired window is followed in a fresh window', async () => {
    const state = serveCheckout()
    state.onCharge = (n) => aPayment({ id: `payment-${n}`, status: 'PENDING' })
    const user = userWithTimers()
    renderRoute(PATH)
    await user.click(await payButton())
    await vi.advanceTimersByTimeAsync(121_000)
    await screen.findByText(/Vẫn đang xử lý/)

    // The first payment turns out to have failed; the student comes back to the tab (TanStack
    // refetches on focus, no button involved), pays again and gets a new PENDING payment.
    state.payment = aPayment({ id: 'payment-1', status: 'FAILED' })
    window.dispatchEvent(new Event('visibilitychange'))
    await screen.findByText(/Thanh toán bị từ chối/)
    await user.click(await payButton())
    await screen.findByText('Đang xử lý thanh toán…')
    const reads = state.paymentReads
    await vi.advanceTimersByTimeAsync(2_100)

    expect(screen.queryByText(/Vẫn đang xử lý/)).not.toBeInTheDocument()
    await waitFor(() => expect(state.paymentReads).toBeGreaterThan(reads))
  })

  it('keeps following the payment named in the URL after a reload', async () => {
    const state = serveCheckout({ payment: aPayment({ status: 'PENDING' }) })
    renderRoute(`${PATH}?payment=${PAYMENT_ID}`)
    expect(await screen.findByText('Đang xử lý thanh toán…')).toBeInTheDocument()

    state.payment = aPayment({ status: 'SUCCEEDED' })
    await vi.advanceTimersByTimeAsync(2_100)

    expect(await screen.findByText('Thanh toán thành công.')).toBeInTheDocument()
  })
})

describe('CheckoutPage: when paying fails', () => {
  it('says at once that the gateway is unavailable and that the seat is still held (503)', async () => {
    const state = serveCheckout()
    state.onCharge = () => problem(503, { retryAfter: 30 })
    const user = userEvent.setup()
    renderRoute(PATH)

    await user.click(await payButton())

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Cổng thanh toán tạm thời không khả dụng. Chỗ của bạn vẫn được giữ trong 15 phút',
    )
    expect(state.charges).toHaveLength(1) // no automatic retry of a charge
    expect(screen.getByRole('button', { name: 'Thanh toán' })).toBeEnabled()
  })

  it('the next try after a 503 carries the same key, so a charge can never happen twice', async () => {
    const state = serveCheckout()
    state.onCharge = (n) => (n === 1 ? problem(503) : aPayment({ status: 'SUCCEEDED' }))
    const user = userEvent.setup()
    renderRoute(PATH)

    await user.click(await payButton())
    await screen.findByRole('alert')
    await user.click(await payButton())

    expect(await screen.findByText('Thanh toán thành công.')).toBeInTheDocument()
    expect(state.charges[1].key).toBe(state.charges[0].key)
  })

  it('a different token is a different request: it gets its own key', async () => {
    const state = serveCheckout()
    state.onCharge = () => problem(503)
    const user = userEvent.setup()
    renderRoute(PATH)

    await user.click(await payButton())
    await screen.findByRole('alert')
    await user.selectOptions(screen.getByLabelText(/Phương thức thanh toán/), 'pm_card_chargeDeclined')
    await user.click(await payButton())

    await waitFor(() => expect(state.charges).toHaveLength(2))
    expect(state.charges[1].key).not.toBe(state.charges[0].key)
  })

  it('a network failure keeps the key too', async () => {
    const state = serveCheckout()
    state.onCharge = (n) => (n === 1 ? HttpResponse.error() : aPayment({ status: 'SUCCEEDED' }))
    const user = userEvent.setup()
    renderRoute(PATH)

    await user.click(await payButton())
    expect(await screen.findByRole('alert')).toHaveTextContent('Mất kết nối')
    await user.click(await payButton())

    expect(await screen.findByText('Thanh toán thành công.')).toBeInTheDocument()
    expect(state.charges[1].key).toBe(state.charges[0].key)
  })

  it.each([
    ['registration-not-reserved', 'không còn được giữ chỗ'],
    ['payment-in-progress', 'Đang có một thanh toán'],
    ['idempotency-key-in-use', 'vẫn đang được xử lý'],
  ])('shows the reason of a 409 %s', async (slug, message) => {
    const state = serveCheckout()
    state.onCharge = () => problem(409, { slug })
    const user = userEvent.setup()
    renderRoute(PATH)

    await user.click(await payButton())

    expect(await screen.findByRole('alert')).toHaveTextContent(message)
  })

  it('falls back to the status for a 409 type it does not know (AC8.3)', async () => {
    const state = serveCheckout()
    state.onCharge = () => problem(409, { slug: 'added-after-this-client-shipped' })
    const user = userEvent.setup()
    renderRoute(PATH)

    await user.click(await payButton())

    expect(await screen.findByRole('alert')).toHaveTextContent('Không thể thanh toán ở trạng thái hiện tại')
  })
})

describe('CheckoutPage: the registration it pays for', () => {
  it('shows the remaining hold as minutes and seconds', async () => {
    serveCheckout({
      registration: aReservedRegistration({ holdExpiresAt: new Date(Date.now() + 10 * 60 * 1000).toISOString() }),
    })
    renderRoute(PATH)

    expect(await screen.findByText(/Chỗ của bạn được giữ trong/)).toHaveTextContent(/\b(09|10):\d\d\b/)
  })

  it('disables paying once the hold has run out', async () => {
    serveCheckout({
      registration: aReservedRegistration({ holdExpiresAt: new Date(Date.now() - 1000).toISOString() }),
    })
    renderRoute(PATH)

    expect(await screen.findByText('Đã hết thời gian giữ chỗ.')).toBeInTheDocument()
    expect(await payButton()).toBeDisabled()
  })

  it('a confirmed registration needs no payment form', async () => {
    serveCheckout({ registration: aRegistration({ ticketType: 'PAID', status: 'CONFIRMED' }) })
    renderRoute(PATH)

    expect(await screen.findByText(/Đăng ký của bạn đã được xác nhận/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Thanh toán' })).not.toBeInTheDocument()
  })

  it('an expired registration whose payment needs a refund says so, with no form and no polling', async () => {
    const state = serveCheckout({
      registration: aRegistration({ ticketType: 'PAID', status: 'EXPIRED' }),
      payment: aPayment({ status: 'REFUND_REQUIRED' }),
    })
    renderRoute(`${PATH}?payment=${PAYMENT_ID}`)

    expect(await screen.findByText(/Khoản tiền sẽ được hoàn lại/)).toBeInTheDocument()
    expect(screen.getByText('Đã hết thời gian giữ chỗ cho đăng ký này.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Thanh toán' })).not.toBeInTheDocument()
    expect(state.paymentReads).toBe(1)
  })

  it.each([
    [403, 'Bạn không phải là người đăng ký này.'],
    [404, 'Không tìm thấy đăng ký.'],
  ])('shows a %s for the registration', async (status, message) => {
    serveCheckout()
    server.use(http.get('/api/v1/registrations/:id', () => problem(status)))
    renderRoute(PATH)

    expect(await screen.findByRole('alert')).toHaveTextContent(message)
  })
})

describe('CheckoutPage: who may open it', () => {
  it('sends an anonymous visitor to sign-in', async () => {
    serveCheckout()
    // beforeEach signed in; end it so the visit is anonymous
    const { endSession } = await import('@/features/auth')
    endSession()
    const { router } = renderRoute(PATH)

    await waitFor(() => expect(router.state.location.pathname).toBe('/login'))
  })

  it('shows a no-permission state to a session without REGISTRATION_CREATE', async () => {
    serveCheckout()
    signInAs({ permissions: [Permission.WORKSHOP_EDIT] })
    renderRoute(PATH)

    expect(await screen.findByRole('alert')).toHaveTextContent('không có quyền')
  })

  it('a 401 ends the session and returns to sign-in (AC14.3)', async () => {
    serveCheckout()
    server.use(http.get('/api/v1/registrations/:id', () => problem(401)))
    const { router } = renderRoute(PATH)

    await waitFor(() => expect(router.state.location.pathname).toBe('/login'))
    expect(sessionStorage.getItem('unihub.session')).toBeNull()
    expect(router.state.location.state).toMatchObject({ from: { pathname: PATH } })
  })
})
