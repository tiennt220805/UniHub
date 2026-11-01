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
    // seatsTaken changed: refresh the detail. WorkshopSummary carries no seat count, so the catalog
    // lists need no invalidation for a registration.
    void queryClient.invalidateQueries({ queryKey: workshopKeys.detail(workshopId) })
    // A free registration is already CONFIRMED. A paid one is RESERVED and must be paid for.
    if (registration.ticketType === 'PAID') {
      void navigate(`/me/checkout/${registration.id}`)
    }
  }

  return (
    <main className="mx-auto max-w-3xl p-6">
      <Link to="/workshops" className="text-sm text-muted-foreground hover:underline">
        ← Danh sách workshop
      </Link>
      {session !== null && hasAnyPermission(session, [Permission.REPORT_VIEW]) && (
        <Link
          to={`/admin/workshops/${workshopId}/report`}
          className="ml-4 text-sm text-muted-foreground hover:underline"
        >
          Xem báo cáo
        </Link>
      )}
      <WorkshopDetailView query={workshop}>
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
