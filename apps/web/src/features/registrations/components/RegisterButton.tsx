import { Link, useLocation } from 'react-router'

import { MAX_AUTOMATIC_RETRIES } from '@/shared/api/retry'
import { Button } from '@/shared/ui/button'
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
      <Link to="/login" state={{ from: location }} className="text-primary underline">
        Đăng nhập để đăng ký
      </Link>
    )
  }
  if (!canRegister) {
    return (
      <p className="text-sm text-muted-foreground">Tài khoản của bạn không có quyền đăng ký workshop.</p>
    )
  }
  if (registration) {
    return (
      <p role="status" className="rounded-md bg-muted p-3 text-foreground">
        {registration.status === 'CONFIRMED'
          ? 'Đăng ký thành công. Chỗ của bạn đã được xác nhận.'
          : 'Đã giữ chỗ cho bạn. Đang chuyển tới thanh toán…'}
      </p>
    )
  }

  return (
    <div className="space-y-2">
      <Button onClick={register} disabled={isPending}>
        {isPending ? 'Đang đăng ký…' : 'Đăng ký tham dự'}
      </Button>
      {isPending && retryCount > 0 && (
        <p role="status" className="text-sm text-muted-foreground">
          Hệ thống đang bận, đang thử lại (lần {retryCount}/{MAX_AUTOMATIC_RETRIES})…
        </p>
      )}
      {error && !isPending && (
        <p role="alert" className="text-sm text-destructive">
          {registrationErrorMessage(error)}
          {error.problem?.correlationId && (
            <span className="block">Mã tham chiếu: {error.problem.correlationId}</span>
          )}
        </p>
      )}
    </div>
  )
}
