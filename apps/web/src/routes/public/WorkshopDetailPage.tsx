import { Link, useParams } from 'react-router'

import { useWorkshop, WorkshopDetailView } from '@/features/workshops'

// getWorkshop is public (paths/workshop.yaml: security: []); a DRAFT is visible only to a caller
// holding WORKSHOP_CREATE/WORKSHOP_EDIT, which needs the sign-in UI that does not exist yet.
export function WorkshopDetailPage() {
  const { workshopId = '' } = useParams()
  const workshop = useWorkshop(workshopId)

  return (
    <main className="mx-auto max-w-3xl p-6">
      <Link to="/workshops" className="text-sm text-muted-foreground hover:underline">
        ← Danh sách workshop
      </Link>
      <WorkshopDetailView query={workshop} />
    </main>
  )
}
