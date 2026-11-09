import { CircleAlert, CircleCheck, Clock, Info, Loader2, Lock } from 'lucide-react'
import { Link, useLocation } from 'react-router'

import { problemSlug } from '@/shared/api/errors'
import { MAX_AUTOMATIC_RETRIES } from '@/shared/api/retry'
import { Button } from '@/shared/ui/button'
import { Notice } from '@/shared/ui/notice'
import { useRegisterForWorkshop } from '../api/mutations'
import type { Registration } from '../api/requests'
import { registrationErrorMessage } from '../model/error-messages'

type Props = {
  workshopId: string
  /** Decided by the route from the session; a feature never reads the session itself. */
  signedIn: boolean
  canRegister: boolean
  /** Fires once, with the new registration. A paid one is RESERVED and still needs payment. */
  onRegistered: (registration: Registration) => void
}

export function RegisterButton({ workshopId, signedIn, canRegister, onRegistered }: Props) {
  const location = useLocation()
  const { register, isPending, retryCount, error, registration } = useRegisterForWorkshop(
    workshopId,
    { onSuccess: onRegistered },
  )

  if (!signedIn) {
    return (
      <Button asChild size="lg" className="w-full">
        <Link to="/login" state={{ from: location }}>
          Đăng nhập để đăng ký
        </Link>
      </Button>
    )
  }
  if (!canRegister) {
    return (
      <Notice tone="muted" icon={Lock}>
        Tài khoản của bạn không có quyền đăng ký workshop.
      </Notice>
    )
  }
  if (registration) {
    return registration.status === 'CONFIRMED' ? (
      <div className="grid gap-2">
        <Notice tone="success" icon={CircleCheck} role="status">
          Đăng ký thành công. Chỗ của bạn đã được xác nhận.
        </Notice>
        <Link to={`/me/registrations/${registration.id}`} className="text-sm font-semibold text-primary-text underline-offset-4 hover:underline">
          Xem vé →
        </Link>
      </div>
    ) : (
      <Notice tone="warning" icon={Clock} role="status">
        Đã giữ chỗ cho bạn. Đang chuyển tới thanh toán…
      </Notice>
    )
  }

  const slug = error ? problemSlug(error) : null
  // After a 409 already-registered there is nothing left to try: the way on is the registration the user already has (D143).
  if (error && !isPending && slug === 'already-registered') {
    return (
      <div className="grid gap-3">
        <Notice tone="info" icon={Info} role="alert">
          <b className="block font-semibold">{registrationErrorMessage(error)}</b>
          {error.problem?.correlationId && <span className="block">Mã tham chiếu: {error.problem.correlationId}</span>}
        </Notice>
        <Button asChild size="lg" className="w-full">
          <Link to="/me/registrations">Xem đăng ký của tôi</Link>
        </Button>
      </div>
    )
  }

  return (
    <div className="grid gap-3">
      {error && !isPending && (
        <Notice tone="destructive" icon={CircleAlert} role="alert">
          <b className="block font-semibold">{registrationErrorMessage(error)}</b>
          {slug === 'sold-out' && <span className="block">Chỗ giữ nhưng chưa thanh toán có thể được trả lại sau 15 phút.</span>}
          {error.problem?.correlationId && <span className="block">Mã tham chiếu: {error.problem.correlationId}</span>}
        </Notice>
      )}
      <Button size="lg" className="w-full" onClick={register} disabled={isPending}>
        {isPending && <Loader2 aria-hidden="true" className="motion-safe:animate-spin" />}
        {isPending ? 'Đang đăng ký…' : 'Đăng ký tham dự'}
      </Button>
      {isPending && retryCount > 0 && (
        <p role="status" className="text-sm text-muted-foreground">
          Hệ thống đang bận, đang thử lại (lần {retryCount}/{MAX_AUTOMATIC_RETRIES})…
        </p>
      )}
    </div>
  )
}
