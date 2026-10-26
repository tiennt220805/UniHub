import { useQueryClient } from '@tanstack/react-query'
import { useEffect } from 'react'
import { Link, useParams, useSearchParams } from 'react-router'

import {
  CheckoutForm,
  isPaymentResolved,
  isPaymentRetryable,
  type Payment,
  PaymentStatusNotice,
  usePayment,
} from '@/features/payments'
import {
  HoldCountdown,
  isRegistrationFinal,
  registrationKeys,
  useRegistration,
} from '@/features/registrations'
import type { AppError } from '@/shared/api/errors'
import { env } from '@/shared/config/env'
import { useCountdown } from '@/shared/hooks/use-countdown'
import { usePollWindow } from '@/shared/hooks/use-poll-window'
import { Button } from '@/shared/ui/button'
import { WorkshopName } from './WorkshopName'

const STATUS_POLL_MS = 2_000

const loadErrorMessage = (error: AppError) =>
  error.status === 403
    ? 'Bạn không phải là người đăng ký này.'
    : error.status === 404 || error.status === 400
      ? 'Không tìm thấy đăng ký.'
      : 'Không tải được thông tin đăng ký.'

/**
 * Checkout of a paid registration (checklist D10, D11, D15). The page shows what the server says
 * and never a guess ahead of it: the payment's status as returned, then the registration's until it
 * leaves RESERVED. The id of the payment being followed lives in the URL (`?payment=`), so a reload
 * keeps following it.
 */
export function CheckoutPage() {
  const { registrationId = '' } = useParams()
  const [searchParams, setSearchParams] = useSearchParams()
  const paymentId = searchParams.get('payment')
  const queryClient = useQueryClient()

  const registration = useRegistration(registrationId)
  const payment = usePayment(paymentId)
  const holdLeft = useCountdown(registration.data?.holdExpiresAt)

  // Poll while there is a payment to follow and nothing has settled: not a failure the student can
  // retry, not a refund, and the registration still RESERVED — which includes the window where the
  // payment has SUCCEEDED but the registration is not yet confirmed.
  const paymentEndedWithoutConfirming =
    payment.data !== undefined && isPaymentResolved(payment.data.status) && payment.data.status !== 'SUCCEEDED'
  const settled =
    registration.data !== undefined &&
    (isRegistrationFinal(registration.data.status) || paymentEndedWithoutConfirming)
  const active =
    paymentId !== null && registration.data !== undefined && !payment.isError && !settled
  const pollWindow = usePollWindow(active)

  const { refetch: refetchRegistration } = registration
  const { refetch: refetchPayment } = payment
  useEffect(() => {
    if (!pollWindow.polling) return
    const timer = setInterval(() => {
      void refetchRegistration()
      void refetchPayment()
    }, STATUS_POLL_MS)
    return () => clearInterval(timer)
  }, [pollWindow.polling, refetchRegistration, refetchPayment])

  const onCharged = (charged: Payment) => {
    setSearchParams({ payment: charged.id }, { replace: true })
    // The payment may already have moved the registration; read it again, and the lists that show it.
    void queryClient.invalidateQueries({ queryKey: registrationKeys.detail(registrationId) })
    void queryClient.invalidateQueries({ queryKey: registrationKeys.lists() })
  }

  if (registration.isPending) {
    return <Frame>Đang tải…</Frame>
  }
  if (registration.isError) {
    return (
      <Frame>
        <p role="alert">{loadErrorMessage(registration.error)}</p>
      </Frame>
    )
  }

  const current = registration.data
  const canPay =
    current.status === 'RESERVED' && (payment.data === undefined || isPaymentRetryable(payment.data.status))

  return (
    <Frame>
      <p className="text-muted-foreground">
        <WorkshopName workshopId={current.workshopId} />
      </p>

      {current.status === 'CONFIRMED' && (
        <p role="status" className="rounded-md bg-muted p-3 text-foreground">
          Đăng ký của bạn đã được xác nhận.{' '}
          <Link to="/me/registrations" className="underline">
            Xem đăng ký của tôi
          </Link>
        </p>
      )}
      {(current.status === 'EXPIRED' || current.status === 'CANCELLED') && (
        <p role="status" className="rounded-md bg-muted p-3 text-foreground">
          {current.status === 'EXPIRED'
            ? 'Đã hết thời gian giữ chỗ cho đăng ký này.'
            : 'Đăng ký này đã bị hủy.'}
        </p>
      )}
      {current.status === 'RESERVED' && <HoldCountdown registration={current} />}

      {paymentId !== null && payment.isPending && <p>Đang tải trạng thái thanh toán…</p>}
      {payment.isError && (
        <p role="alert">Không tải được trạng thái thanh toán. Vui lòng thử làm mới trang.</p>
      )}
      {payment.data && <PaymentStatusNotice payment={payment.data} />}
      {payment.data?.status === 'SUCCEEDED' && current.status === 'RESERVED' && (
        <p role="status">Đang xác nhận đăng ký của bạn…</p>
      )}

      {pollWindow.expired && (
        <div className="space-y-2">
          <p role="status">Vẫn đang xử lý. Kết quả sẽ hiện ra ngay khi có.</p>
          <Button
            variant="outline"
            onClick={() => {
              pollWindow.restart()
              void refetchRegistration()
              void refetchPayment()
            }}
          >
            Làm mới
          </Button>
        </div>
      )}

      {canPay && (
        <CheckoutForm
          registrationId={registrationId}
          testMode={env.paymentTestMode}
          disabled={holdLeft === 0}
          onCharged={onCharged}
        />
      )}
    </Frame>
  )
}

function Frame({ children }: { children: React.ReactNode }) {
  return (
    <main className="mx-auto max-w-3xl space-y-4 p-6">
      <h1 className="text-2xl font-semibold text-foreground">Thanh toán</h1>
      {children}
    </main>
  )
}
