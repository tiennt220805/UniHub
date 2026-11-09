import { cn } from '@/shared/lib/utils'
import { dayBadge } from '@/shared/lib/vn-time'
import { coverArt } from '../model/cover-art'

type Props = {
  workshopId: string
  /** A same-origin path (`/media/…`) once the organizer has uploaded a cover; `null` until then (D123). */
  coverImageUrl: string | null
  /** Draws the date badge (a card has one, the detail page does not). */
  startTime?: string
  /** A cancelled workshop's cover recedes. */
  dimmed?: boolean
  className?: string
}

// Decorative (`alt=""`): the title sits right beside it and says what the workshop is.
export function WorkshopCover({ workshopId, coverImageUrl, startTime, dimmed, className }: Props) {
  const badge = startTime ? dayBadge(startTime) : null
  return (
    <div
      data-slot="workshop-cover"
      className={cn('relative aspect-video overflow-hidden bg-primary-soft', dimmed && 'opacity-60 grayscale', className)}
    >
      {coverImageUrl ? (
        <img src={coverImageUrl} alt="" loading="lazy" decoding="async" className="size-full object-cover" />
      ) : (
        <GeneratedCover workshopId={workshopId} />
      )}
      {badge && (
        <div className="absolute top-3 left-3 rounded-lg bg-card px-2.5 py-1 text-center leading-tight text-card-foreground shadow-sm">
          <b className="block text-xl font-bold">{badge.day}</b>
          <span className="text-xs font-medium text-muted-foreground">{badge.month}</span>
        </div>
      )}
    </div>
  )
}

// Presentation attributes only (fill, transform): no <style> and no style="", both refused by the CSP's `style-src`.
function GeneratedCover({ workshopId }: { workshopId: string }) {
  const art = coverArt(workshopId)
  const cx = 30 + art.x * 100
  const cy = 15 + art.y * 60
  return (
    <svg viewBox="0 0 160 90" preserveAspectRatio="xMidYMid slice" aria-hidden="true" className="absolute inset-0 size-full">
      <rect width="160" height="90" fill={art.background} />
      {art.family === 'sun' && (
        <>
          <circle cx={cx} cy={cy} r="48" fill={art.primary} />
          <rect
            x={20 + art.y * 60}
            y={25 + art.x * 20}
            width="70"
            height="70"
            rx="12"
            fill={art.secondary}
            transform={`rotate(${art.turn} ${55 + art.y * 60} ${60 + art.x * 20})`}
          />
        </>
      )}
      {art.family === 'bars' && (
        <g transform={`rotate(${art.turn} 80 45)`}>
          <rect x="-20" y={8 + art.y * 10} width="200" height="22" rx="11" fill={art.primary} />
          <rect x={-20 + art.x * 40} y="40" width="200" height="22" rx="11" fill={art.secondary} />
          <rect x="-20" y={72 - art.y * 8} width="200" height="22" rx="11" fill={art.primary} />
        </g>
      )}
      {art.family === 'tiles' && (
        <g transform={`rotate(${art.turn} 80 45)`}>
          {[0, 1, 2].flatMap((col) =>
            [0, 1].map((row) => (
              <rect
                key={`${col}-${row}`}
                x={18 + col * 46 + art.x * 8}
                y={8 + row * 44 + art.y * 6}
                width="40"
                height="38"
                rx="10"
                fill={(col + row) % 2 === 0 ? art.primary : art.secondary}
              />
            )),
          )}
        </g>
      )}
    </svg>
  )
}
