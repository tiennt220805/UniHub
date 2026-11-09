import { Link } from 'react-router'

import { useWorkshop, WorkshopCover } from '@/features/workshops'
import { formatDateTime } from '@/shared/lib/format'
import { Badge } from '@/shared/ui/badge'
import { Skeleton } from '@/shared/ui/skeleton'
import { useHasStarted } from './use-has-started'

// A registration carries only its workshop's id, so the routes of /me compose what they show of the workshop from here.
// Every part reads the same `getWorkshop` query: the cache serves the second and third part of a row without a request.
// None of them blocks the row: while the workshop loads they are skeletons, and if it cannot be read the row keeps its
// words (the status, the ticket) and the title falls back to "Workshop".

export function WorkshopThumbnail({ workshopId }: { workshopId: string }) {
  const workshop = useWorkshop(workshopId)
  if (workshop.isPending) return <Skeleton className="aspect-video rounded-lg" />
  return (
    <WorkshopCover
      workshopId={workshopId}
      coverImageUrl={workshop.data?.coverImageUrl ?? null}
      className="rounded-lg"
    />
  )
}

export function WorkshopHeading({ workshopId }: { workshopId: string }) {
  const workshop = useWorkshop(workshopId)
  if (workshop.isPending) {
    return (
      <div className="space-y-2">
        <Skeleton className="h-5 w-3/5" />
        <Skeleton className="h-4 w-2/5" />
      </div>
    )
  }
  return (
    <div>
      <Link to={`/workshops/${workshopId}`} className="font-semibold leading-snug hover:underline">
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

export function PastFlag({ workshopId }: { workshopId: string }) {
  return useHasStarted(workshopId) ? <Badge tone="muted">Đã qua</Badge> : null
}
