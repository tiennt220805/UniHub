import { Link } from 'react-router'

import { useWorkshop } from '@/features/workshops'

/**
 * A workshop's title as a link to its page. A registration carries only the workshop id, so the
 * route that lists or details registrations composes this in; the two features never meet.
 */
export function WorkshopName({ workshopId }: { workshopId: string }) {
  const workshop = useWorkshop(workshopId)
  return (
    <Link to={`/workshops/${workshopId}`} className="font-medium hover:underline">
      {workshop.data?.title ?? 'Workshop'}
    </Link>
  )
}
