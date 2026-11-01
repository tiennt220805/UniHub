import { Link, useParams } from 'react-router'

import { RegistrationTicketView, useRegistration } from '@/features/registrations'
import { useWorkshop } from '@/features/workshops'
import type { AppError } from '@/shared/api/errors'
import { formatDateTime } from '@/shared/lib/format'

const loadErrorMessage = (error: AppError) =>
  error.status === 403
    ? 'Bạn không phải là người đăng ký này.'
    : error.status === 404 || error.status === 400
      ? 'Không tìm thấy đăng ký.'
      : error.status === 429
        ? 'Bạn đang gửi quá nhiều yêu cầu. Vui lòng thử lại sau.'
        : 'Không tải được thông tin đăng ký.'

/**
 * The e-ticket of one of the signed-in user's registrations (checklist D56). `getRegistration`
 * answers `403` for someone else's registration and the page then shows a refusal and nothing of
 * the ticket (S-18). The ticket itself is drawn from the registration's status, see `ticketStateOf`.
 */
export function RegistrationTicketPage() {
  const { registrationId = '' } = useParams()
  const registration = useRegistration(registrationId)

  return (
    <main className="mx-auto max-w-3xl space-y-4 p-6">
      <Link to="/me/registrations" className="text-sm text-muted-foreground hover:underline">
        ← Đăng ký của tôi
      </Link>
      <h1 className="text-2xl font-semibold text-foreground">E-ticket</h1>
      {registration.isPending && <p>Đang tải…</p>}
      {registration.isError && (
        <p role="alert">
          {loadErrorMessage(registration.error)}
          {registration.error.problem?.correlationId && (
            <span className="block text-sm">Mã tham chiếu: {registration.error.problem.correlationId}</span>
          )}
        </p>
      )}
      {registration.data && (
        <>
          <RegistrationTicketView
            registration={registration.data}
            workshop={<TicketWorkshop workshopId={registration.data.workshopId} />}
          />
          {registration.data.status === 'RESERVED' && registration.data.ticketType === 'PAID' && (
            <Link to={`/me/checkout/${registration.data.id}`} className="text-sm text-primary underline">
              Thanh toán để nhận vé
            </Link>
          )}
        </>
      )}
    </main>
  )
}

// The ticket stays usable when the workshop cannot be read: only its name and time are lost.
function TicketWorkshop({ workshopId }: { workshopId: string }) {
  const workshop = useWorkshop(workshopId)
  return (
    <div>
      <Link to={`/workshops/${workshopId}`} className="text-lg font-medium hover:underline">
        {workshop.data?.title ?? 'Workshop'}
      </Link>
      {workshop.data && (
        <p className="text-sm text-muted-foreground">
          <time dateTime={workshop.data.startTime}>{formatDateTime(workshop.data.startTime)}</time>
          {' · '}
          {workshop.data.location}
        </p>
      )}
    </div>
  )
}
