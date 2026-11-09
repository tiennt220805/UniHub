import { useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useParams } from 'react-router'

import { Permission, hasAnyPermission, useSession } from '@/features/auth'
import { RegisterButton, type Registration } from '@/features/registrations'
import { useWorkshop, WorkshopDetailView, workshopKeys } from '@/features/workshops'

// getWorkshop is public (paths/workshop.yaml: security: []); the token is still sent, because a
// DRAFT is visible only to a caller holding WORKSHOP_CREATE/WORKSHOP_EDIT.
export function WorkshopDetailPage() {
  const { workshopId = '' } = useParams()
  const workshop = useWorkshop(workshopId, { liveSeats: true })
  const session = useSession() // the route reads the session; features only get booleans
  const queryClient = useQueryClient()
  const navigate = useNavigate()

  const onRegistered = (registration: Registration) => {
    // seatsTaken changed: refresh the detail. The catalog cards carry a seat level from a list the backend caches for
    // 30 s, so they catch up on their own when the list is read again; no invalidation is needed here.
    void queryClient.invalidateQueries({ queryKey: workshopKeys.detail(workshopId) })
    // A free registration is already CONFIRMED. A paid one is RESERVED and must be paid for.
    if (registration.ticketType === 'PAID') {
      void navigate(`/me/checkout/${registration.id}`)
    }
  }

  return (
    <main className="mx-auto max-w-6xl px-4 py-6 md:px-6 md:py-8">
      <div className="flex flex-wrap items-center gap-x-5 text-sm">
        <Link to="/workshops" className="inline-flex min-h-11 items-center font-medium text-muted-foreground hover:underline">
          ← Danh sách workshop
        </Link>
        {session !== null && hasAnyPermission(session, [Permission.REPORT_VIEW]) && (
          <Link
            to={`/admin/workshops/${workshopId}/report`}
            className="inline-flex min-h-11 items-center font-medium text-muted-foreground hover:underline"
          >
            Xem báo cáo
          </Link>
        )}
      </div>
      <WorkshopDetailView query={workshop} catalogPath="/workshops">
        <RegisterButton
          workshopId={workshopId}
          signedIn={session !== null}
          canRegister={session !== null && hasAnyPermission(session, [Permission.REGISTRATION_CREATE])}
          onRegistered={onRegistered}
        />
      </WorkshopDetailView>
    </main>
  )
}
