import { MapPin, User } from 'lucide-react'
import { Link } from 'react-router'

import { formatPrice } from '@/shared/lib/format'
import { clock } from '@/shared/lib/vn-time'
import { Badge } from '@/shared/ui/badge'
import { Card } from '@/shared/ui/card'
import type { WorkshopSummary } from '../api/requests'
import { SeatMeter } from './SeatMeter'
import { WorkshopCover } from './WorkshopCover'

type Props = {
  workshop: WorkshopSummary
  /** URL of the workshop's detail page: owned by the route table, not by this feature. */
  detailPath: string
}

// The whole card is one target: the title link stretches over it (`after:inset-0`), so the link's name is just the
// title, and a keyboard user meets one link per card. Everything on it comes from the list response (D117): the
// card never asks for the workshop's detail.
export function WorkshopCard({ workshop, detailPath }: Props) {
  return (
    <Card className="group relative flex h-full flex-col transition-[box-shadow,transform] duration-150 ease-out-strong motion-safe:active:scale-[0.98] focus-within:ring-2 focus-within:ring-ring hover:shadow-md">
      <WorkshopCover workshopId={workshop.id} coverImageUrl={workshop.coverImageUrl ?? null} startTime={workshop.startTime} />
      <div className="flex flex-1 flex-col gap-2 p-4">
        <div className="flex items-center justify-between gap-2">
          <time dateTime={workshop.startTime} className="text-sm font-semibold text-primary-text">
            {clock(workshop.startTime)} – {clock(workshop.endTime)}
          </time>
          {workshop.isPaid && workshop.price != null ? (
            <Badge tone="highlight">Có phí · {formatPrice(workshop.price)}</Badge>
          ) : (
            <Badge tone="muted">Miễn phí</Badge>
          )}
        </div>
        <h3 className="text-xl leading-7 font-semibold text-foreground">
          <Link
            to={detailPath}
            className="outline-none after:absolute after:inset-0 after:content-[''] group-hover:underline"
          >
            {workshop.title}
          </Link>
        </h3>
        <p className="flex items-start gap-1.5 text-sm text-muted-foreground">
          <User aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
          <span className="min-w-0 [overflow-wrap:anywhere]">{workshop.speakerName}</span>
        </p>
        <p className="flex items-start gap-1.5 text-sm text-muted-foreground">
          <MapPin aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
          <span className="min-w-0 [overflow-wrap:anywhere]">{workshop.location}</span>
        </p>
        <SeatMeter capacity={workshop.capacity} seatsTaken={workshop.seatsTaken} className="mt-auto pt-2" />
      </div>
    </Card>
  )
}

export function WorkshopCardGrid({ items, detailPath }: { items: WorkshopSummary[]; detailPath: (workshopId: string) => string }) {
  return (
    <ul className="grid gap-6 sm:grid-cols-2 lg:grid-cols-3">
      {items.map((workshop) => (
        <li key={workshop.id} className="min-w-0">
          <WorkshopCard workshop={workshop} detailPath={detailPath(workshop.id)} />
        </li>
      ))}
    </ul>
  )
}
