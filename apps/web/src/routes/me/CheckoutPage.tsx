import { useQueryClient } from '@tanstack/react-query'
import { useEffect } from 'react'
import { CircleCheck, CircleX, Loader2, Info, TimerOff } from 'lucide-react'
import { Link, useParams, useSearchParams } from 'react-router'

import {
  CheckoutForm,
  isPaymentResolved,
  isPaymentRetryable,
  isRefundPending,
  type Payment,
  PaymentStatusNotice,
  usePayment,
} from '@/features/payments'
import {
  HoldCountdown,
  isRegistrationFinal,
  registrationKeys,
  RegistrationStatusBadge,
  useRegistration,
} from '@/features/registrations'
import { useWorkshop } from '@/features/workshops'
import type { AppError } from '@/shared/api/errors'
import { LoadError } from '@/shared/components/LoadError'
import { env } from '@/shared/config/env'
import { useCountdown } from '@/shared/hooks/use-countdown'
import { usePollWindow } from '@/shared/hooks/use-poll-window'
import { Button } from '@/shared/ui/button'
import { Notice } from '@/shared/ui/notice'
import { Skeleton } from '@/shared/ui/skeleton'
import { CheckoutSteps } from './CheckoutSteps'
import { CheckoutSummary } from './CheckoutSummary'

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
  const workshopPrice = useWorkshop(registration.data?.workshopId ?? '', { enabled: registration.data !== undefined }).data?.price ?? null

  // Poll while there is a payment to follow and nothing has settled: not a failure the student can
  // retry, and the registration still RESERVED — which includes the window where the payment has
  // SUCCEEDED but the registration is not yet confirmed.
  //
  // A registration that has ended (EXPIRED, CANCELLED) does not end the payment's story: a charge
  // still PENDING can succeed late, a late success becomes REFUND_REQUIRED and the refund consumer
  // then moves it to REFUNDED, all after the registration is final (Phase 3). Those payments are
  // followed until they reach FAILED, CANCELLED or REFUNDED.
  const paymentEndedWithoutConfirming =
    payment.data !== undefined && isPaymentResolved(payment.data.status) && payment.data.status !== 'SUCCEEDED'
  const registrationEnded =
    registration.data !== undefined &&
    registration.data.status !== 'CONFIRMED' &&
    isRegistrationFinal(registration.data.status)
  const paymentStillMoving =
    payment.data !== undefined &&
    registration.data !== undefined &&
    registration.data.status !== 'CONFIRMED' &&
    (!isPaymentResolved(payment.data.status) ||
      isRefundPending(payment.data.status) ||
      (payment.data.status === 'SUCCEEDED' && registrationEnded))
  const settled =
    registration.data !== undefined &&
    !paymentStillMoving &&
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
    return (
      <Frame>
        <div role="status" aria-busy="true" className="grid gap-4">
          <span className="sr-only">Đang tải…</span>
          <Skeleton className="h-40 rounded-xl" />
          <Skeleton className="h-48 rounded-xl" />
        </div>
      </Frame>
    )
  }
  if (registration.isError) {
    return (
      <Frame>
        <LoadError
          error={registration.error}
          message={loadErrorMessage(registration.error)}
          onRetry={() => void registration.refetch()}
        />
      </Frame>
    )
  }

  const current = registration.data
  const canPay =
    current.status === 'RESERVED' && (payment.data === undefined || isPaymentRetryable(payment.data.status))
  // Once the money is taken a countdown means nothing: what is left is the confirmation, which is not on a clock (D148).
  const showCountdown = current.status === 'RESERVED' && payment.data?.status !== 'SUCCEEDED'
  const showSteps = current.status === 'RESERVED' || current.status === 'CONFIRMED'

  return (
    <Frame workshopId={current.workshopId}>
      <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_360px] lg:items-start lg:gap-x-6">
        {showCountdown && (
          // Below `lg` this is a bar that stays under the top bar while the page scrolls; from `lg` it is the top of the side column.
          <div className="sticky top-14 z-30 -mx-4 border-b border-border bg-background px-4 py-2 md:top-16 md:-mx-6 md:px-6 lg:static lg:col-start-2 lg:row-start-1 lg:mx-0 lg:border-0 lg:bg-transparent lg:p-0">
            <HoldCountdown registration={current} size="lg" />
            <p className="mt-2 hidden text-xs text-muted-foreground lg:block">
              Hết giờ giữ chỗ, chỗ được trả lại cho người khác.
            </p>
          </div>
        )}

        <div className="grid gap-5 lg:col-start-1 lg:row-span-2 lg:row-start-1">
          <CheckoutSummary workshopId={current.workshopId} />

          {current.status === 'CONFIRMED' && (
            <div className="grid gap-3">
              <Notice tone="success" icon={CircleCheck} role="status">
                Đăng ký của bạn đã được xác nhận.{' '}
                <Link to="/me/registrations" className="font-semibold underline underline-offset-4">
                  Xem đăng ký của tôi
                </Link>
              </Notice>
              <Button asChild size="lg" className="h-12">
                <Link to={`/me/registrations/${current.id}`}>Xem vé</Link>
              </Button>
            </div>
          )}
          {current.status === 'EXPIRED' && (
            <div className="grid gap-2">
              <Notice tone="muted" icon={TimerOff} role="status">
                Đã hết thời gian giữ chỗ cho đăng ký này.
              </Notice>
              <Link to="/workshops" className="text-sm font-semibold text-primary-text underline-offset-4 hover:underline">
                Xem workshop khác
              </Link>
            </div>
          )}
          {current.status === 'CANCELLED' && (
            <Notice tone="destructive" icon={CircleX} role="status">
              Đăng ký này đã bị hủy.
            </Notice>
          )}

          {paymentId !== null && payment.isPending && (
            <Notice tone="info" icon={Loader2} role="status" className="[&>svg]:motion-safe:animate-spin">
              Đang tải trạng thái thanh toán…
            </Notice>
          )}
          {payment.isError && (
            <Notice tone="destructive" icon={Info} role="alert">
              Không tải được trạng thái thanh toán. Vui lòng thử làm mới trang.
            </Notice>
          )}
          {payment.data && <PaymentStatusNotice payment={payment.data} />}
          {payment.data?.status === 'SUCCEEDED' && current.status === 'RESERVED' && (
            <Notice tone="info" icon={Loader2} role="status" className="[&>svg]:motion-safe:animate-spin">
              Đang xác nhận đăng ký của bạn…
            </Notice>
          )}

          {pollWindow.expired && (
            <div className="grid justify-items-start gap-2">
              <Notice tone="muted" icon={Info} role="status" className="w-full">
                Vẫn đang xử lý. Kết quả sẽ hiện ra ngay khi có.
              </Notice>
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
              amount={payment.data?.amount ?? workshopPrice}
              onCharged={onCharged}
            />
          )}
        </div>

        {showSteps && (
          <aside
            aria-label="Tiến trình"
            className={`hidden rounded-xl border border-border bg-card p-5 lg:col-start-2 lg:block ${showCountdown ? 'lg:row-start-2' : 'lg:row-start-1'}`}
          >
            <div className="mb-5 flex items-center justify-between gap-2">
              <h2 className="text-lg leading-6 font-semibold">Giữ chỗ</h2>
              <RegistrationStatusBadge status={current.status} />
            </div>
            <CheckoutSteps registration={current} payment={payment.data} />
          </aside>
        )}
      </div>
    </Frame>
  )
}

// A link back to the workshop once it is known, then the heading. "Thanh toán" is the page's one heading of that name
// (the E2E finds the page by it), so no other heading here may contain the words.
function Frame({ children, workshopId }: { children: React.ReactNode; workshopId?: string }) {
  return (
    <main className="mx-auto max-w-6xl px-4 py-4 md:px-6 md:py-6">
      {workshopId ? (
        <Link
          to={`/workshops/${workshopId}`}
          className="inline-flex min-h-11 items-center text-sm font-medium text-muted-foreground hover:underline"
        >
          ← Chi tiết workshop
        </Link>
      ) : (
        <div className="min-h-11" />
      )}
      <h1 className="mb-5 text-[28px] leading-9 font-bold tracking-tight md:text-3xl md:leading-[38px]">Thanh toán</h1>
      {children}
    </main>
  )
}
