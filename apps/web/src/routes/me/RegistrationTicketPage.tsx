import { Link, useParams } from 'react-router'

import { RegistrationTicketView, useRegistration } from '@/features/registrations'
import { useWorkshop } from '@/features/workshops'
import type { AppError } from '@/shared/api/errors'
import { LoadError } from '@/shared/components/LoadError'
import { formatDateTime } from '@/shared/lib/format'
import { Button } from '@/shared/ui/button'
import { Skeleton } from '@/shared/ui/skeleton'

const loadErrorMessage = (error: AppError) =>
  error.status === 403
    ? 'Bạn không phải là người đăng ký này.'
    : error.status === 404 || error.status === 400
      ? 'Không tìm thấy đăng ký.'
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
    <main className="mx-auto max-w-4xl px-4 py-4 md:px-6 md:py-6">
      <Link
        to="/me/registrations"
        className="inline-flex min-h-11 items-center text-sm font-medium text-muted-foreground hover:underline"
      >
        ← Đăng ký của tôi
      </Link>
      <h1 className="mb-5 text-[28px] leading-9 font-bold tracking-tight md:text-3xl md:leading-[38px]">E-ticket</h1>
      {registration.isPending && (
        <div role="status" aria-busy="true" className="grid gap-3">
          <span className="sr-only">Đang tải…</span>
          <Skeleton className="h-6 w-1/2" />
          <Skeleton className="size-56 rounded-xl" />
          <Skeleton className="h-4 w-4/5" />
        </div>
      )}
      {registration.isError && (
        <LoadError
          error={registration.error}
          message={loadErrorMessage(registration.error)}
          onRetry={() => void registration.refetch()}
        />
      )}
      {registration.data && (
        <div className="grid gap-4">
          <RegistrationTicketView
            registration={registration.data}
            workshop={<TicketWorkshop workshopId={registration.data.workshopId} />}
          />
          {registration.data.status === 'RESERVED' && registration.data.ticketType === 'PAID' && (
            <Button asChild size="lg" className="w-full sm:w-fit">
              <Link to={`/me/checkout/${registration.data.id}`}>Thanh toán để nhận vé</Link>
            </Button>
          )}
        </div>
      )}
    </main>
  )
}

// The ticket stays usable when the workshop cannot be read: only its name and time are lost.
function TicketWorkshop({ workshopId }: { workshopId: string }) {
  const workshop = useWorkshop(workshopId)
  if (workshop.isPending) {
    return (
      <div className="space-y-2">
        <Skeleton className="h-7 w-4/5" />
        <Skeleton className="h-4 w-3/5" />
      </div>
    )
  }
  return (
    <div className="space-y-1.5">
      <Link
        to={`/workshops/${workshopId}`}
        className="block text-xl leading-7 font-bold tracking-tight hover:underline md:text-2xl md:leading-8"
      >
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
